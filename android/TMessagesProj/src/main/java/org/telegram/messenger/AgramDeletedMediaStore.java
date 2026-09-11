/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * App-private, per-container archive for media belonging to ordinary messages
 * which Telegram deleted on the server. The archive deliberately lives below
 * filesDir rather than any FileLoader cache directory, so clearing Telegram's
 * cache or FilePathDatabase cannot remove or orphan it.
 */
public final class AgramDeletedMediaStore {

    private static final String ARCHIVE_DIRECTORY = "deleted_media/v1";
    private static final String JOURNAL_DIRECTORY = "deleted_media/pending_v1";
    private static final String JOURNAL_SUFFIX = ".pending";
    private static final String PURGE_PREFIX = ".agram-delete-";
    private static final String PURGE_SUFFIX = ".tombstone";
    private static final int COPY_BUFFER_SIZE = 256 * 1024;
    private static final int MAX_PENDING_MESSAGES = 8192;
    private static final int MAX_SOURCE_PATHS = 16;
    private static final int MAX_PURGED_MESSAGE_GUARDS = 16384;
    private static final int ORPHAN_RECONCILE_BATCH_SIZE = 64;
    private static final int RECONCILE_RETRY_DELAY_MS = 30_000;
    private static final long PENDING_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final DispatchQueue archiveQueue = new DispatchQueue("agramDeletedMediaArchive");
    private static final Object pendingLock = new Object();
    private static final Object archiveIoLock = new Object();
    private static final Map<String, PendingEntry> pendingMessages = new LinkedHashMap<>();
    private static final Set<String> pendingSources = new HashSet<>();
    private static final Set<String> cleanedArchiveRoots = new HashSet<>();
    private static final Set<String> loadedJournalRoots = new HashSet<>();
    private static final Set<String> invalidatedContainers = new HashSet<>();
    private static final Set<String> purgedMessageGuards = new LinkedHashSet<>();
    private static final Set<String> reconciledArchiveRoots = new HashSet<>();

    private AgramDeletedMediaStore() {
    }

    /**
     * Captures and pins possible source paths. MessagesStorage calls this before committing the
     * deletion marker, closing the cache-cleanup race without starting file I/O in its transaction.
     */
    public static PreparedArchive prepareArchive(int account, TLRPC.Message message) {
        if (!isEligible(account, message)) {
            return null;
        }
        ContainerSnapshot snapshot = captureContainer(account);
        ArchiveRequest request = createRequest(snapshot, message, null);
        if (request == null) {
            return null;
        }
        if (!isCurrentContainer(snapshot)) {
            return null;
        }
        PreparedArchive prepared = new PreparedArchive(request.messageKey, UUID.randomUUID().toString(), snapshot);
        boolean created = false;
        boolean journalSaved;
        synchronized (pendingLock) {
            if (isInvalidatedLocked(snapshot)
                    || isPurgedLocked(snapshot, message.dialog_id, message.id)) {
                return null;
            }
            prunePendingLocked();
            // Cache deletion is serialized on this same lock. Rechecking the source while the
            // lock is held closes the observe-file/register-pin versus delete race.
            boolean hasReadableSource = findReadableSource(request) != null;
            boolean primaryDownloadInFlight = FileLoader.getInstance(account)
                    .isLoadingFile(FileLoader.getMessageFileName(message));
            PendingEntry entry = pendingMessages.get(request.messageKey);
            if (entry == null) {
                // Do not leave journals for media which is neither present nor actually being
                // downloaded. Such an entry cannot ever be fulfilled after server deletion.
                if (!hasReadableSource && !primaryDownloadInFlight) {
                    return null;
                }
                if (pendingMessages.size() >= MAX_PENDING_MESSAGES) {
                    // Never trade an older durable promise for a newer one. Refusing the new
                    // entry is conservative and keeps every already-pinned source protected.
                    FileLog.e("Deleted-media pending journal is full; refusing a new entry");
                    return null;
                }
                entry = new PendingEntry(request);
                pendingMessages.put(request.messageKey, entry);
                created = true;
            }
            entry.prepareTokens.add(prepared.tokenId);
            mergeSourcesLocked(entry, request.sourcePaths, false);
            if (hasReadableSource && !entry.readableSourceObserved) {
                entry.readableSourceObserved = true;
                if (!created) {
                    entry.sourceGeneration++;
                }
            }
            entry.lastTouched = SystemClock.elapsedRealtime();
            journalSaved = persistJournalLocked(entry);
            if (!journalSaved && created) {
                forgetPendingLocked(request.messageKey);
            }
        }
        if (!journalSaved || !isCurrentContainer(snapshot)) {
            cancelPreparedArchive(prepared);
            return null;
        }
        return prepared;
    }

    /** Starts I/O only after the database transaction which persisted the marker succeeded. */
    public static void commitPreparedArchive(PreparedArchive prepared) {
        if (prepared == null) {
            return;
        }
        if (!isCurrentContainer(prepared.snapshot)) {
            cancelPreparedArchive(prepared);
            return;
        }
        ArchiveRequest request;
        boolean downloadFailed;
        PendingReconcile reconcile = null;
        synchronized (pendingLock) {
            prunePendingLocked();
            PendingEntry entry = pendingMessages.get(prepared.messageKey);
            if (!matches(entry, prepared.snapshot) || !entry.prepareTokens.remove(prepared.tokenId)) {
                return;
            }
            entry.committed = true;
            entry.reconciling = false;
            entry.lastTouched = SystemClock.elapsedRealtime();
            if (!persistJournalLocked(entry)) {
                // SQLite is already committed. Keep the pre-commit journal and source pin, then
                // reconcile/rewrite instead of copying and prematurely removing either one.
                entry.reconciling = true;
                reconcile = new PendingReconcile(entry.messageKey, entry.snapshot,
                        entry.dialogId, entry.messageId);
            }
            request = entry.toRequest();
            downloadFailed = entry.downloadFailed;
        }
        if (reconcile != null) {
            retryReconcile(reconcile);
        } else if (isCompleteArchive(request.destination, request.expectedMediaSize)
                && syncDirectory(request.destination.getParentFile())) {
            completePending(request.messageKey);
        } else if (findReadableSource(request) != null) {
            enqueue(request);
        } else if (downloadFailed) {
            forgetPending(request.messageKey);
        }
    }

    public static void cancelPreparedArchive(PreparedArchive prepared) {
        if (prepared == null) {
            return;
        }
        synchronized (pendingLock) {
            PendingEntry entry = pendingMessages.get(prepared.messageKey);
            if (!matches(entry, prepared.snapshot)) {
                return;
            }
            entry.prepareTokens.remove(prepared.tokenId);
            if (!entry.committed && entry.prepareTokens.isEmpty()) {
                forgetPendingLocked(prepared.messageKey);
            } else {
                persistJournalLocked(entry);
            }
        }
    }

    /** Convenience path for archive backfill outside a storage transaction. */
    public static void archiveMessage(int account, TLRPC.Message message) {
        PreparedArchive prepared = prepareArchive(account, message);
        commitPreparedArchive(prepared);
    }

    /** Captured when FileLoader creates an operation; it must never migrate to a new container. */
    public static DownloadToken captureDownloadToken(int account) {
        ContainerSnapshot snapshot = captureContainer(account);
        return snapshot == null ? null : new DownloadToken(snapshot);
    }

    /**
     * Registers a primary download which was started after a message had already been marked as
     * deleted. The operation has already been added to FileLoader's queue when this is called.
     */
    public static void beginDeletedMediaDownload(DownloadToken downloadToken, int account,
                                                 MessageObject messageObject, String operationFileName) {
        if (downloadToken == null || messageObject == null || messageObject.messageOwner == null
                || downloadToken.snapshot.account != account || account != messageObject.currentAccount) {
            return;
        }
        TLRPC.Message message = messageObject.messageOwner;
        if (!TextUtils.equals(FileLoader.getMessageFileName(message), operationFileName)
                || !isEligible(account, message) || !isCurrentContainer(downloadToken.snapshot)) {
            return;
        }
        ArchiveRequest request = createRequest(downloadToken.snapshot, message, null);
        if (request == null) {
            return;
        }
        synchronized (pendingLock) {
            if (isInvalidatedLocked(downloadToken.snapshot)
                    || isPurgedLocked(downloadToken.snapshot, message.dialog_id, message.id)) {
                return;
            }
            prunePendingLocked();
            PendingEntry entry = pendingMessages.get(request.messageKey);
            boolean created = false;
            if (entry == null) {
                if (pendingMessages.size() >= MAX_PENDING_MESSAGES) {
                    FileLog.e("Deleted-media pending journal is full; refusing a download entry");
                    return;
                }
                entry = new PendingEntry(request);
                pendingMessages.put(entry.messageKey, entry);
                created = true;
            } else if (!matches(entry, downloadToken.snapshot)) {
                return;
            }
            mergeSourcesLocked(entry, request.sourcePaths, false);
            if (!entry.downloadInFlight) {
                entry.downloadInFlight = true;
                entry.sourceGeneration++;
            }
            entry.downloadFailed = false;
            entry.committed = true;
            entry.reconciling = false;
            entry.lastTouched = SystemClock.elapsedRealtime();
            if (!persistJournalLocked(entry) && created) {
                forgetPendingLocked(entry.messageKey);
            }
        }
    }

    /** Removes all in-memory pins as part of explicit logout/container destruction. */
    public static void purgeContainer(int account, String containerId) {
        synchronized (pendingLock) {
            if (!TextUtils.isEmpty(containerId)) {
                invalidatedContainers.add(containerIdentity(account, containerId));
                String guardPrefix = containerIdentity(account, containerId) + ":";
                purgedMessageGuards.removeIf(key -> key.startsWith(guardPrefix));
                if (ApplicationLoader.applicationContext != null) {
                    File directory = new File(ApplicationLoader.applicationContext.getFilesDir(),
                            "agram_containers" + File.separator + containerId);
                    cleanedArchiveRoots.remove(canonicalPath(new File(directory, ARCHIVE_DIRECTORY)));
                    loadedJournalRoots.remove(canonicalPath(new File(directory, JOURNAL_DIRECTORY)));
                    reconciledArchiveRoots.remove(canonicalPath(new File(directory, ARCHIVE_DIRECTORY)));
                }
            }
            ArrayList<String> keys = new ArrayList<>();
            for (PendingEntry entry : pendingMessages.values()) {
                if (entry.snapshot.account == account
                        && (TextUtils.isEmpty(containerId) || TextUtils.equals(containerId, entry.snapshot.containerId))) {
                    keys.add(entry.messageKey);
                    cleanedArchiveRoots.remove(canonicalPath(entry.snapshot.archiveRoot));
                    loadedJournalRoots.remove(canonicalPath(entry.snapshot.journalRoot));
                    reconciledArchiveRoots.remove(canonicalPath(entry.snapshot.archiveRoot));
                }
            }
            for (String key : keys) {
                forgetPendingLocked(key);
            }
        }
        // Invalidation is visible before waiting for archive I/O. A worker already holding the
        // I/O lock will now fail its next current-container check without calling back into the
        // manager, then release the lock. Once this barrier returns, renaming the container
        // cannot race a copy which recreates its old directory.
        synchronized (archiveIoLock) {
            // Barrier only.
        }
    }

    /** Removes the durable archive only after the exact local message row was deleted. */
    public static void purgeMessage(int account, long dialogId, int messageId) {
        ContainerSnapshot snapshot = captureContainer(account);
        if (snapshot == null) {
            return;
        }
        synchronized (pendingLock) {
            addPurgedMessageGuardLocked(snapshot, dialogId, messageId);
            ArrayList<String> keys = new ArrayList<>();
            for (PendingEntry entry : pendingMessages.values()) {
                if (matches(entry, snapshot) && entry.dialogId == dialogId && entry.messageId == messageId) {
                    keys.add(entry.messageKey);
                }
            }
            for (String key : keys) {
                forgetPendingLocked(key);
            }
        }
        File messageDirectory = getMessageArchiveDirectory(snapshot, dialogId, messageId);
        if (messageDirectory != null) {
            tombstoneArchiveDirectory(snapshot, messageDirectory);
        }
    }

    /** Clear-history/dialog deletion owns every archived message below this dialog directory. */
    public static void purgeDialog(int account, long dialogId, List<Integer> deletedMessageIds) {
        ContainerSnapshot snapshot = captureContainer(account);
        if (snapshot == null) {
            return;
        }
        File dialogDirectory = getDialogArchiveDirectory(snapshot, dialogId);
        File[] archivedChildren = dialogDirectory != null ? dialogDirectory.listFiles() : null;
        synchronized (pendingLock) {
            addPurgedMessageGuardsLocked(snapshot, dialogId, deletedMessageIds, null);
            addArchivedDirectoryGuardsLocked(snapshot, dialogId, archivedChildren, null);
            ArrayList<String> keys = new ArrayList<>();
            for (PendingEntry entry : pendingMessages.values()) {
                if (matches(entry, snapshot) && entry.dialogId == dialogId) {
                    addPurgedMessageGuardLocked(snapshot, dialogId, entry.messageId);
                    keys.add(entry.messageKey);
                }
            }
            for (String key : keys) {
                forgetPendingLocked(key);
            }
        }
        if (dialogDirectory != null) {
            tombstoneArchiveDirectory(snapshot, dialogDirectory);
        }
    }

    /** Clear-history variant which preserves the archive for the dialog's retained last rows. */
    public static void purgeDialogExcept(int account, long dialogId,
                                         List<Integer> deletedMessageIds,
                                         long retainedMessageId1, long retainedMessageId2) {
        ContainerSnapshot snapshot = captureContainer(account);
        if (snapshot == null) {
            return;
        }
        Set<Integer> retainedIds = new HashSet<>();
        addRetainedMessageId(retainedIds, retainedMessageId1);
        addRetainedMessageId(retainedIds, retainedMessageId2);
        File dialogDirectory = getDialogArchiveDirectory(snapshot, dialogId);
        File[] children = dialogDirectory != null ? dialogDirectory.listFiles() : null;
        synchronized (pendingLock) {
            addPurgedMessageGuardsLocked(snapshot, dialogId, deletedMessageIds, retainedIds);
            addArchivedDirectoryGuardsLocked(snapshot, dialogId, children, retainedIds);
            ArrayList<String> keys = new ArrayList<>();
            for (PendingEntry entry : pendingMessages.values()) {
                if (matches(entry, snapshot) && entry.dialogId == dialogId
                        && entry.messageId != retainedMessageId1
                        && entry.messageId != retainedMessageId2) {
                    addPurgedMessageGuardLocked(snapshot, dialogId, entry.messageId);
                    keys.add(entry.messageKey);
                }
            }
            for (String key : keys) {
                forgetPendingLocked(key);
            }
        }
        if (dialogDirectory == null || !dialogDirectory.isDirectory()) {
            return;
        }
        Set<String> retainedDirectoryNames = new HashSet<>();
        addRetainedMessageDirectoryName(retainedDirectoryNames, retainedMessageId1);
        addRetainedMessageDirectoryName(retainedDirectoryNames, retainedMessageId2);
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child == null ? null : child.getName();
            if (!isMessageArchiveDirectoryName(name) || retainedDirectoryNames.contains(name)) {
                continue;
            }
            tombstoneArchiveDirectory(snapshot, child);
        }
    }

    private static void addRetainedMessageDirectoryName(Set<String> names, long messageId) {
        if (messageId >= Integer.MIN_VALUE && messageId <= Integer.MAX_VALUE) {
            names.add("m_" + Integer.toUnsignedString((int) messageId, 16));
        }
    }

    private static void addRetainedMessageId(Set<Integer> ids, long messageId) {
        if (messageId >= Integer.MIN_VALUE && messageId <= Integer.MAX_VALUE) {
            ids.add((int) messageId);
        }
    }

    /** Must only be called while pendingLock is held. */
    private static void addPurgedMessageGuardsLocked(ContainerSnapshot snapshot, long dialogId,
                                                      List<Integer> messageIds, Set<Integer> retainedIds) {
        if (messageIds == null) {
            return;
        }
        for (int i = 0; i < messageIds.size(); i++) {
            Integer messageId = messageIds.get(i);
            if (messageId != null && (retainedIds == null || !retainedIds.contains(messageId))) {
                addPurgedMessageGuardLocked(snapshot, dialogId, messageId);
            }
        }
    }

    /** Must only be called while pendingLock is held. */
    private static void addArchivedDirectoryGuardsLocked(ContainerSnapshot snapshot, long dialogId,
                                                          File[] children, Set<Integer> retainedIds) {
        if (children == null) {
            return;
        }
        for (File child : children) {
            Integer messageId = parseMessageArchiveDirectoryId(child == null ? null : child.getName());
            if (messageId != null && (retainedIds == null || !retainedIds.contains(messageId))) {
                addPurgedMessageGuardLocked(snapshot, dialogId, messageId);
            }
        }
    }

    private static boolean isMessageArchiveDirectoryName(String name) {
        if (TextUtils.isEmpty(name) || !name.startsWith("m_")
                || name.length() < 3 || name.length() > 10) {
            return false;
        }
        for (int i = 2; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static Integer parseMessageArchiveDirectoryId(String name) {
        if (!isMessageArchiveDirectoryName(name)) {
            return null;
        }
        try {
            return Integer.parseUnsignedInt(name.substring(2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseDialogArchiveDirectoryId(String name) {
        if (TextUtils.isEmpty(name) || !name.startsWith("d_")
                || name.length() < 3 || name.length() > 18) {
            return null;
        }
        for (int i = 2; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return null;
            }
        }
        try {
            return Long.parseUnsignedLong(name.substring(2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A process can die after SQLite deletes a retained-message row but before its archive is
     * tombstoned. Reconcile deterministic archive directories in small DB batches on first use;
     * only a definite absence of the exact marker permits deletion.
     */
    private static void ensureArchiveReconciliation(ContainerSnapshot snapshot) {
        String rootPath = canonicalPath(snapshot == null ? null : snapshot.archiveRoot);
        if (rootPath == null) {
            return;
        }
        synchronized (pendingLock) {
            if (isInvalidatedLocked(snapshot) || !reconciledArchiveRoots.add(rootPath)) {
                return;
            }
        }
        archiveQueue.postRunnable(() -> {
            ArrayList<ArchivedMessagePath> archivedMessages = scanArchivedMessages(snapshot);
            if (archivedMessages == null) {
                synchronized (pendingLock) {
                    reconciledArchiveRoots.remove(rootPath);
                }
                archiveQueue.postRunnable(() -> ensureArchiveReconciliation(snapshot),
                        RECONCILE_RETRY_DELAY_MS);
                return;
            }
            reconcileArchivedMessageBatch(snapshot, archivedMessages, 0);
        });
    }

    /** Returns null for an I/O/listing failure, an empty list when the root does not exist. */
    private static ArrayList<ArchivedMessagePath> scanArchivedMessages(ContainerSnapshot snapshot) {
        ArrayList<ArchivedMessagePath> result = new ArrayList<>();
        if (snapshot == null || !snapshot.archiveRoot.exists()) {
            return result;
        }
        File[] dialogDirectories = snapshot.archiveRoot.listFiles();
        if (dialogDirectories == null) {
            return null;
        }
        HashSet<String> seen = new HashSet<>();
        for (File dialogDirectory : dialogDirectories) {
            Long dialogId = parseDialogArchiveDirectoryId(
                    dialogDirectory == null ? null : dialogDirectory.getName());
            if (dialogId == null || !dialogDirectory.isDirectory()
                    || !isCanonicalDirectChild(snapshot.archiveRoot, dialogDirectory)) {
                continue;
            }
            File[] messageDirectories = dialogDirectory.listFiles();
            if (messageDirectories == null) {
                return null;
            }
            for (File messageDirectory : messageDirectories) {
                Integer messageId = parseMessageArchiveDirectoryId(
                        messageDirectory == null ? null : messageDirectory.getName());
                if (messageId == null || !messageDirectory.isDirectory()
                        || !isCanonicalDirectChild(dialogDirectory, messageDirectory)) {
                    continue;
                }
                String key = dialogId + ":" + messageId;
                if (seen.add(key)) {
                    result.add(new ArchivedMessagePath(dialogId, messageId));
                }
            }
        }
        return result;
    }

    private static boolean isCanonicalDirectChild(File parent, File child) {
        try {
            return parent != null && child != null
                    && parent.getCanonicalFile().equals(child.getCanonicalFile().getParentFile());
        } catch (IOException e) {
            return false;
        }
    }

    private static void reconcileArchivedMessageBatch(ContainerSnapshot snapshot,
                                                        ArrayList<ArchivedMessagePath> messages,
                                                        int offset) {
        if (snapshot == null || messages == null || offset >= messages.size()
                || !isCurrentContainer(snapshot)) {
            return;
        }
        int count = Math.min(ORPHAN_RECONCILE_BATCH_SIZE, messages.size() - offset);
        long[] dialogIds = new long[count];
        int[] messageIds = new int[count];
        for (int i = 0; i < count; i++) {
            ArchivedMessagePath message = messages.get(offset + i);
            dialogIds[i] = message.dialogId;
            messageIds[i] = message.messageId;
        }
        try {
            MessagesStorage.getInstance(snapshot.account).reconcileAgramDeletedMediaMarkers(
                    dialogIds, messageIds, results -> {
                        if (results == null || results.length != count || hasUnknownResult(results)) {
                            archiveQueue.postRunnable(() -> reconcileArchivedMessageBatch(
                                            snapshot, messages, offset),
                                    RECONCILE_RETRY_DELAY_MS);
                            return;
                        }
                        // This callback runs on the account's storageQueue. Reserve exact absent
                        // IDs before returning, so a later marker transaction cannot race the
                        // DB result and have its newly-created archive removed as an orphan.
                        if (!reserveOrphanPurges(snapshot, messages, offset, results)) {
                            return;
                        }
                        archiveQueue.postRunnable(() -> {
                            if (!isCurrentContainer(snapshot)) {
                                return;
                            }
                            for (int i = 0; i < count; i++) {
                                if (results[i] == 0) {
                                    ArchivedMessagePath message = messages.get(offset + i);
                                    purgeArchivedMessage(snapshot, message.dialogId, message.messageId);
                                }
                            }
                            reconcileArchivedMessageBatch(snapshot, messages, offset + count);
                        });
                    });
        } catch (Throwable e) {
            FileLog.e("Unable to schedule deleted-media orphan reconciliation", e);
            archiveQueue.postRunnable(() -> reconcileArchivedMessageBatch(
                            snapshot, messages, offset), RECONCILE_RETRY_DELAY_MS);
        }
    }

    private static boolean hasUnknownResult(int[] results) {
        for (int result : results) {
            if (result < 0) {
                return true;
            }
        }
        return false;
    }

    /** Called synchronously from MessagesStorage's serial queue after the exact DB query. */
    private static boolean reserveOrphanPurges(ContainerSnapshot snapshot,
                                               ArrayList<ArchivedMessagePath> messages,
                                               int offset, int[] results) {
        if (!isCurrentContainer(snapshot)) {
            return false;
        }
        synchronized (pendingLock) {
            if (isInvalidatedLocked(snapshot)) {
                return false;
            }
            ArrayList<String> keys = new ArrayList<>();
            for (int i = 0; i < results.length; i++) {
                if (results[i] != 0) {
                    continue;
                }
                ArchivedMessagePath message = messages.get(offset + i);
                addPurgedMessageGuardLocked(snapshot, message.dialogId, message.messageId);
                for (PendingEntry entry : pendingMessages.values()) {
                    if (matches(entry, snapshot) && entry.dialogId == message.dialogId
                            && entry.messageId == message.messageId) {
                        keys.add(entry.messageKey);
                    }
                }
            }
            for (String key : keys) {
                forgetPendingLocked(key);
            }
        }
        return true;
    }

    private static void purgeArchivedMessage(ContainerSnapshot snapshot,
                                               long dialogId, int messageId) {
        if (!isCurrentContainer(snapshot)) {
            return;
        }
        synchronized (pendingLock) {
            if (isInvalidatedLocked(snapshot)) {
                return;
            }
            addPurgedMessageGuardLocked(snapshot, dialogId, messageId);
            ArrayList<String> keys = new ArrayList<>();
            for (PendingEntry entry : pendingMessages.values()) {
                if (matches(entry, snapshot) && entry.dialogId == dialogId
                        && entry.messageId == messageId) {
                    keys.add(entry.messageKey);
                }
            }
            for (String key : keys) {
                forgetPendingLocked(key);
            }
        }
        File messageDirectory = getMessageArchiveDirectory(snapshot, dialogId, messageId);
        if (messageDirectory != null) {
            tombstoneArchiveDirectory(snapshot, messageDirectory);
        }
    }

    /** Archives a download that completed after the server-deletion update arrived. */
    public static void archiveDownloaded(DownloadToken downloadToken, int account, MessageObject messageObject,
                                         String loadedFileName, File finalFile) {
        if (messageObject == null || messageObject.messageOwner == null || finalFile == null
                || downloadToken == null || downloadToken.snapshot.account != account
                || account != messageObject.currentAccount) {
            return;
        }
        TLRPC.Message message = messageObject.messageOwner;
        if (!TextUtils.equals(FileLoader.getMessageFileName(message), loadedFileName)) {
            // didFinishLoadingFile also reports thumbnails, alternate video qualities and
            // live-photo companions with the same MessageObject parent. Never store one of
            // those bytes under the primary media's deterministic archive name.
            return;
        }
        if (!isEligible(account, message)) {
            return;
        }
        if (!isCurrentContainer(downloadToken.snapshot)) {
            return;
        }
        String messageKey = buildMessageKey(downloadToken.snapshot, message);
        ArchiveRequest request = null;
        synchronized (pendingLock) {
            prunePendingLocked();
            PendingEntry entry = pendingMessages.get(messageKey);
            if (!matches(entry, downloadToken.snapshot)) {
                return;
            }
            String finalPath = canonicalPath(finalFile);
            String destinationPath = canonicalPath(entry.destination);
            if (finalPath == null || finalPath.equals(destinationPath)) {
                return;
            }
            if (entry.expectedMediaSize > 0 && finalFile.length() != entry.expectedMediaSize) {
                return;
            }
            boolean sourceChanged = mergeSourcesLocked(
                    entry, java.util.Collections.singletonList(finalPath), true);
            if (!sourceChanged) {
                // The final path may already be listed while the download is incomplete.
                entry.sourceGeneration++;
            }
            entry.readableSourceObserved = true;
            entry.downloadInFlight = false;
            entry.downloadFailed = false;
            entry.lastTouched = SystemClock.elapsedRealtime();
            persistJournalLocked(entry);
            if (entry.committed) {
                request = entry.toRequest();
            }
        }
        if (request != null && findReadableSource(request) != null) {
            enqueue(request);
        }
    }

    /** Releases a missing-download pin; a usable alternate source still gets one final attempt. */
    public static void forgetDownload(DownloadToken downloadToken, int account,
                                      MessageObject messageObject, String loadedFileName) {
        if (downloadToken == null || messageObject == null || messageObject.messageOwner == null
                || downloadToken.snapshot.account != account || account != messageObject.currentAccount
                || !TextUtils.equals(FileLoader.getMessageFileName(messageObject.messageOwner), loadedFileName)) {
            return;
        }
        if (!isCurrentContainer(downloadToken.snapshot)) {
            return;
        }
        String messageKey = buildMessageKey(downloadToken.snapshot, messageObject.messageOwner);
        ArchiveRequest request = null;
        synchronized (pendingLock) {
            prunePendingLocked();
            PendingEntry entry = pendingMessages.get(messageKey);
            if (!matches(entry, downloadToken.snapshot)) {
                return;
            }
            entry.downloadFailed = true;
            entry.downloadInFlight = false;
            entry.lastTouched = SystemClock.elapsedRealtime();
            persistJournalLocked(entry);
            if (entry.committed) {
                request = entry.toRequest();
            }
        }
        if (request != null) {
            if (findReadableSource(request) != null) {
                enqueue(request);
            } else {
                forgetPending(messageKey);
            }
        }
    }

    /** Returns an existing archived primary-media file without creating a container. */
    public static File getArchivedFile(int account, TLRPC.Message message) {
        if (!isEligible(account, message)) {
            return null;
        }
        ContainerSnapshot snapshot = captureContainer(account);
        File result = getArchiveFile(snapshot, message);
        return isCompleteArchive(result, getExpectedPrimaryMediaSize(message)) ? result : null;
    }

    /** Exact identity check for callers which must never dispose an app-private archive file. */
    public static boolean isDeletedMediaArchiveFile(int account, TLRPC.Message message, File file) {
        if (message == null || file == null) {
            return false;
        }
        ContainerSnapshot snapshot = captureContainer(account);
        File archive = getArchiveFile(snapshot, message);
        String archivePath = canonicalPath(archive);
        String filePath = canonicalPath(file);
        return archivePath != null && archivePath.equals(filePath);
    }

    /**
     * ImageLoader may request a thumbnail and the primary document separately.
     * Only substitute the archive when its remote file key is the requested key.
     */
    public static File getArchivedFileForImage(int account, TLRPC.Message message,
                                               String requestedFileName, String requestedLocalPath) {
        if (message == null) {
            return null;
        }
        if (!TextUtils.isEmpty(requestedLocalPath) && !TextUtils.isEmpty(message.attachPath)) {
            String requestedPath = canonicalPath(new File(requestedLocalPath));
            String attachPath = canonicalPath(new File(message.attachPath));
            if (requestedPath != null && requestedPath.equals(attachPath)) {
                return getArchivedFile(account, message);
            }
        }
        String primaryFileName = safeLeafName(FileLoader.getMessageFileName(message));
        if (TextUtils.isEmpty(requestedFileName) || TextUtils.isEmpty(primaryFileName)) {
            return null;
        }
        String safeRequestedName = safeLeafName(requestedFileName);
        if (!primaryFileName.equals(safeRequestedName)
                && !isPrimaryPhotoVariant(message, safeRequestedName)) {
            return null;
        }
        return getArchivedFile(account, message);
    }

    /** Used by cache cleanup to close the copy-vs-delete race. */
    public static boolean isPendingSource(File file) {
        ensureCurrentJournalsLoaded();
        String path = canonicalPath(file);
        if (path == null) {
            return false;
        }
        ArrayList<PendingEntry> matchingEntries = new ArrayList<>();
        synchronized (pendingLock) {
            prunePendingLocked();
            if (!pendingSources.contains(path)) {
                return false;
            }
            for (PendingEntry entry : pendingMessages.values()) {
                if (entry.sourcePaths.contains(path)) {
                    matchingEntries.add(entry);
                }
            }
        }
        ArrayList<String> staleKeys = new ArrayList<>();
        boolean live = false;
        for (PendingEntry entry : matchingEntries) {
            if (isCurrentContainer(entry.snapshot)) {
                live = true;
            } else {
                staleKeys.add(entry.messageKey);
            }
        }
        if (!staleKeys.isEmpty()) {
            synchronized (pendingLock) {
                for (String staleKey : staleKeys) {
                    forgetPendingLocked(staleKey);
                }
            }
        }
        return live;
    }

    /**
     * Owns the delete decision and the delete itself under pendingLock. Returns true when the
     * source remains (deferred or deletion failed), false when it is absent/deleted. Callers must
     * never perform a fallback delete after this method returns.
     */
    public static boolean deleteOrDefer(File file) {
        ensureCurrentJournalsLoaded();
        String path = canonicalPath(file);
        if (path == null) {
            return false;
        }
        boolean managed = isManagedMediaPath(path);
        synchronized (pendingLock) {
            prunePendingLocked();
            boolean pending = false;
            for (PendingEntry entry : pendingMessages.values()) {
                if (!isInvalidatedLocked(entry.snapshot) && entry.sourcePaths.contains(path)) {
                    if (managed) {
                        if (entry.cleanupRequestedSources.size() < MAX_SOURCE_PATHS
                                || entry.cleanupRequestedSources.contains(path)) {
                            entry.cleanupRequestedSources.add(path);
                        }
                        entry.lastTouched = SystemClock.elapsedRealtime();
                        persistJournalLocked(entry);
                    }
                    pending = true;
                }
            }
            if (pending) {
                return true;
            }
            // The delete is deliberately inside pendingLock. A concurrent prepareArchive cannot
            // observe this file and then register it after we have decided it was unpinned.
            try {
                return file.exists() && !file.delete();
            } catch (Throwable e) {
                FileLog.e("Unable to delete cache source " + file, e);
                return true;
            }
        }
    }

    private static void ensureCurrentJournalsLoaded() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            captureContainer(account);
        }
    }

    /** Rehydrates durable source pins and resumes them after a process restart. */
    private static void ensureJournalLoaded(ContainerSnapshot snapshot) {
        String journalRootPath = canonicalPath(snapshot.journalRoot);
        if (journalRootPath == null) {
            return;
        }
        synchronized (pendingLock) {
            if (isInvalidatedLocked(snapshot) || !loadedJournalRoots.add(journalRootPath)) {
                return;
            }
        }
        File[] files = snapshot.journalRoot.listFiles();
        if (files == null) {
            return;
        }
        ArrayList<ArchiveRequest> resumable = new ArrayList<>();
        ArrayList<PendingReconcile> unresolved = new ArrayList<>();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            if (isArchiveTemporaryFile(file.getName())) {
                if (!file.delete()) {
                    FileLog.e("Unable to remove incomplete deleted-media journal " + file);
                } else {
                    syncDirectory(file.getParentFile());
                }
                continue;
            }
            if (!isJournalFileName(file.getName())) {
                continue;
            }
            PendingEntry loaded = readJournal(snapshot, file);
            if (loaded == null) {
                if (!file.delete()) {
                    FileLog.e("Unable to remove invalid deleted-media journal " + file);
                } else {
                    syncDirectory(file.getParentFile());
                }
                continue;
            }
            synchronized (pendingLock) {
                prunePendingLocked();
                PendingEntry entry = pendingMessages.get(loaded.messageKey);
                if (entry == null) {
                    if (pendingMessages.size() >= MAX_PENDING_MESSAGES) {
                        // Leave the durable file intact; it can be retried in a later process.
                        continue;
                    }
                    entry = loaded;
                    pendingMessages.put(entry.messageKey, entry);
                } else if (matches(entry, snapshot)) {
                    mergeSourcesLocked(entry, loaded.sourcePaths, false);
                    for (String cleanupSource : loaded.cleanupRequestedSources) {
                        if (entry.cleanupRequestedSources.size() >= MAX_SOURCE_PATHS) {
                            break;
                        }
                        entry.cleanupRequestedSources.add(cleanupSource);
                    }
                    entry.committed |= loaded.committed;
                    entry.downloadFailed |= loaded.downloadFailed;
                    entry.downloadInFlight |= loaded.downloadInFlight;
                    entry.lastTouchedWallTime = Math.max(entry.lastTouchedWallTime, loaded.lastTouchedWallTime);
                    entry.lastTouched = SystemClock.elapsedRealtime();
                } else {
                    continue;
                }
                pendingSources.addAll(entry.sourcePaths);
                if (entry.committed) {
                    resumable.add(entry.toRequest());
                } else {
                    entry.reconciling = true;
                    unresolved.add(new PendingReconcile(entry.messageKey, entry.snapshot,
                            entry.dialogId, entry.messageId));
                }
            }
        }
        for (ArchiveRequest request : resumable) {
            if (isCompleteArchive(request.destination, request.expectedMediaSize)
                    && syncDirectory(request.destination.getParentFile())) {
                completePending(request.messageKey);
            } else if (findReadableSource(request) != null) {
                enqueue(request);
            } else if (isDownloadFailed(request.messageKey)) {
                forgetPending(request.messageKey);
            }
        }
        for (PendingReconcile reconcile : unresolved) {
            requestReconcile(reconcile);
        }
    }

    private static void requestReconcile(PendingReconcile reconcile) {
        if (reconcile == null) {
            return;
        }
        synchronized (pendingLock) {
            if (isInvalidatedLocked(reconcile.snapshot)) {
                forgetPendingLocked(reconcile.messageKey);
                return;
            }
        }
        if (!isCurrentContainer(reconcile.snapshot)) {
            retryReconcile(reconcile);
            return;
        }
        try {
            MessagesStorage.getInstance(reconcile.snapshot.account).reconcileAgramDeletedMediaMarker(
                    reconcile.dialogId, reconcile.messageId,
                    result -> onReconcileResult(reconcile, result == null ? -1 : result));
        } catch (Throwable e) {
            FileLog.e("Unable to schedule deleted-media journal reconciliation", e);
            retryReconcile(reconcile);
        }
    }

    private static void onReconcileResult(PendingReconcile reconcile, int result) {
        if (result < 0) {
            retryReconcile(reconcile);
            return;
        }
        if (result == 0) {
            forgetPending(reconcile.messageKey);
            return;
        }
        if (!isCurrentContainer(reconcile.snapshot)) {
            retryReconcile(reconcile);
            return;
        }
        ArchiveRequest request;
        synchronized (pendingLock) {
            PendingEntry entry = pendingMessages.get(reconcile.messageKey);
            if (!matches(entry, reconcile.snapshot)) {
                return;
            }
            entry.reconciling = false;
            entry.committed = true;
            entry.lastTouched = SystemClock.elapsedRealtime();
            if (!persistJournalLocked(entry)) {
                // Keep the source pinned. A later retry can make the committed state durable.
                entry.reconciling = true;
                retryReconcile(reconcile);
                return;
            }
            request = entry.toRequest();
        }
        if (isCompleteArchive(request.destination, request.expectedMediaSize)
                && syncDirectory(request.destination.getParentFile())) {
            completePending(request.messageKey);
        } else if (findReadableSource(request) != null) {
            enqueue(request);
        } else if (isDownloadFailed(request.messageKey)) {
            forgetPending(request.messageKey);
        }
    }

    private static void retryReconcile(PendingReconcile reconcile) {
        synchronized (pendingLock) {
            PendingEntry entry = pendingMessages.get(reconcile.messageKey);
            if (!matches(entry, reconcile.snapshot)) {
                return;
            }
            entry.reconciling = true;
        }
        archiveQueue.postRunnable(() -> requestReconcile(reconcile), RECONCILE_RETRY_DELAY_MS);
    }

    private static PendingEntry readJournal(ContainerSnapshot snapshot, File file) {
        try {
            long length = file.length();
            if (length <= 0 || length > 1024 * 1024) {
                return null;
            }
            byte[] bytes = new byte[(int) length];
            int offset = 0;
            try (FileInputStream input = new FileInputStream(file)) {
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
            }
            if (offset != bytes.length) {
                return null;
            }
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            int version = json.optInt("version", 0);
            if ((version != 1 && version != 2)
                    || json.optInt("account", -1) != snapshot.account
                    || !TextUtils.equals(json.optString("container_id", ""), snapshot.containerId)) {
                return null;
            }
            String messageKey = json.optString("message_key", "");
            if (TextUtils.isEmpty(messageKey)
                    || !TextUtils.equals(file.getName(), Utilities.MD5(messageKey) + JOURNAL_SUFFIX)) {
                return null;
            }
            String[] keyParts = messageKey.split(":", 5);
            if (keyParts.length != 5 || parseMessageKeyLong(messageKey, 0) != snapshot.account
                    || !TextUtils.equals(keyParts[1], snapshot.containerId)) {
                return null;
            }
            long dialogId = json.optLong("dialog_id", parseMessageKeyLong(messageKey, 2));
            int messageId = json.optInt("message_id", (int) parseMessageKeyLong(messageKey, 3));
            File destination = new File(json.optString("destination", "")).getCanonicalFile();
            String archivePrefix = snapshot.archiveRoot.getPath() + File.separator;
            if (!destination.getPath().startsWith(archivePrefix)) {
                return null;
            }
            File expectedDirectory = getMessageArchiveDirectory(snapshot, dialogId, messageId);
            File expectedDestination = expectedDirectory == null ? null
                    : new File(expectedDirectory, safeLeafName(keyParts[4])).getCanonicalFile();
            if (expectedDestination == null || !expectedDestination.equals(destination)) {
                return null;
            }
            LinkedHashSet<String> sourcePaths = new LinkedHashSet<>();
            JSONArray sources = json.optJSONArray("sources");
            if (sources != null) {
                for (int i = 0; i < sources.length() && i < MAX_SOURCE_PATHS; i++) {
                    String source = canonicalPath(new File(sources.optString(i, "")));
                    if (!TextUtils.isEmpty(source) && !TextUtils.equals(source, destination.getPath())) {
                        sourcePaths.add(source);
                    }
                }
            }
            if (sourcePaths.isEmpty()) {
                return null;
            }
            ArchiveRequest request = new ArchiveRequest(snapshot, messageKey, destination,
                    new ArrayList<>(sourcePaths), Math.max(0, json.optLong("expected_size", 0)),
                    dialogId, messageId);
            PendingEntry entry = new PendingEntry(request);
            entry.committed = json.optBoolean("committed", false);
            entry.downloadFailed = json.optBoolean("download_failed", false);
            entry.downloadInFlight = false;
            JSONArray cleanupSources = json.optJSONArray("cleanup_requested_sources");
            if (cleanupSources != null) {
                for (int i = 0; i < cleanupSources.length() && i < MAX_SOURCE_PATHS; i++) {
                    String source = canonicalPath(new File(cleanupSources.optString(i, "")));
                    if (!TextUtils.isEmpty(source) && sourcePaths.contains(source)
                            && isManagedMediaPath(source)) {
                        entry.cleanupRequestedSources.add(source);
                    }
                }
            }
            entry.lastTouchedWallTime = json.optLong("touched_at", file.lastModified());
            entry.lastTouched = SystemClock.elapsedRealtime();
            entry.readableSourceObserved = findReadableSource(request) != null;
            return entry;
        } catch (Throwable e) {
            FileLog.e("Unable to read deleted-media journal " + file, e);
            return null;
        }
    }

    private static boolean persistJournalLocked(PendingEntry entry) {
        if (entry == null || isInvalidatedLocked(entry.snapshot)
                || !entry.snapshot.containerDirectory.isDirectory()) {
            return false;
        }
        File journalFile = getJournalFile(entry);
        if (journalFile == null) {
            return false;
        }
        File journalRoot = journalFile.getParentFile();
        if (!journalRoot.isDirectory() && !journalRoot.mkdirs()) {
            return false;
        }
        if (isInvalidatedLocked(entry.snapshot)) {
            return false;
        }
        entry.lastTouchedWallTime = System.currentTimeMillis();
        File temporary = new File(journalRoot,
                ".agram-journal-part-" + UUID.randomUUID() + ".tmp");
        try {
            JSONObject json = new JSONObject();
            json.put("version", 2);
            json.put("account", entry.snapshot.account);
            json.put("container_id", entry.snapshot.containerId);
            json.put("message_key", entry.messageKey);
            json.put("dialog_id", entry.dialogId);
            json.put("message_id", entry.messageId);
            json.put("destination", entry.destination.getPath());
            json.put("expected_size", entry.expectedMediaSize);
            json.put("committed", entry.committed);
            json.put("download_failed", entry.downloadFailed);
            json.put("download_in_flight", entry.downloadInFlight);
            json.put("touched_at", entry.lastTouchedWallTime);
            JSONArray sources = new JSONArray();
            int sourceCount = 0;
            for (String source : entry.sourcePaths) {
                if (sourceCount++ >= MAX_SOURCE_PATHS) {
                    break;
                }
                sources.put(source);
            }
            json.put("sources", sources);
            JSONArray cleanupSources = new JSONArray();
            int cleanupCount = 0;
            for (String source : entry.cleanupRequestedSources) {
                if (cleanupCount++ >= MAX_SOURCE_PATHS) {
                    break;
                }
                cleanupSources.put(source);
            }
            json.put("cleanup_requested_sources", cleanupSources);
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            if (isInvalidatedLocked(entry.snapshot)) {
                return false;
            }
            moveAtomically(temporary, journalFile);
            // Sync the destination inode as well. The temporary file was fsynced before its
            // atomic rename, and this makes the final journal durable before SQLite can commit.
            try (FileOutputStream output = new FileOutputStream(journalFile, true)) {
                output.getFD().sync();
            }
            return syncDirectory(journalRoot);
        } catch (Throwable e) {
            FileLog.e("Unable to persist deleted-media journal " + journalFile, e);
            return false;
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                FileLog.e("Unable to remove incomplete deleted-media journal " + temporary);
            }
        }
    }

    private static File getJournalFile(PendingEntry entry) {
        try {
            File result = new File(entry.snapshot.journalRoot,
                    Utilities.MD5(entry.messageKey) + JOURNAL_SUFFIX).getCanonicalFile();
            String prefix = entry.snapshot.journalRoot.getPath() + File.separator;
            return result.getPath().startsWith(prefix) ? result : null;
        } catch (IOException e) {
            FileLog.e("Unable to resolve deleted-media journal path", e);
            return null;
        }
    }

    private static boolean isJournalFileName(String name) {
        if (name == null || !name.endsWith(JOURNAL_SUFFIX) || name.length() != 32 + JOURNAL_SUFFIX.length()) {
            return false;
        }
        for (int i = 0; i < 32; i++) {
            char c = name.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDownloadFailed(String messageKey) {
        synchronized (pendingLock) {
            PendingEntry entry = pendingMessages.get(messageKey);
            return entry != null && entry.downloadFailed;
        }
    }

    private static boolean isEligible(int account, TLRPC.Message message) {
        if (message == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        boolean peerProtected;
        try {
            peerProtected = MessagesController.getInstance(account)
                    .isPeerNoForwards(message.dialog_id);
        } catch (Throwable e) {
            FileLog.e("Unable to validate protected-content state for deleted media", e);
            return false;
        }
        return !peerProtected
                && message.agramDeletedOnServer
                && !message.noforwards
                && MessageObject.canKeepDeletedOnServer(message, message.dialog_id)
                && !TextUtils.isEmpty(FileLoader.getMessageFileName(message));
    }

    private static ContainerSnapshot captureContainer(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ApplicationLoader.applicationContext == null) {
            return null;
        }
        AgramContainerManager.ContainerRecord record;
        try {
            record = AgramContainerManager.getInstance().getContainer(account);
        } catch (Throwable e) {
            FileLog.e("Unable to read Agram container for deleted-media archive", e);
            return null;
        }
        if (record == null || !record.isStorageAccessible() || TextUtils.isEmpty(record.id)) {
            return null;
        }
        try {
            File containersRoot = new File(ApplicationLoader.applicationContext.getFilesDir(), "agram_containers").getCanonicalFile();
            File containerDirectory = new File(containersRoot, record.id).getCanonicalFile();
            if (!containersRoot.equals(containerDirectory.getParentFile())) {
                return null;
            }
            ContainerSnapshot snapshot = new ContainerSnapshot(account, record.id, containersRoot,
                    containerDirectory,
                    new File(containerDirectory, ARCHIVE_DIRECTORY).getCanonicalFile(),
                    new File(containerDirectory, JOURNAL_DIRECTORY).getCanonicalFile());
            cleanupStaleParts(snapshot.archiveRoot);
            ensureJournalLoaded(snapshot);
            ensureArchiveReconciliation(snapshot);
            return snapshot;
        } catch (IOException e) {
            FileLog.e("Unable to resolve deleted-media archive directory", e);
            return null;
        }
    }

    private static ArchiveRequest createRequest(ContainerSnapshot snapshot, TLRPC.Message message, File preferredSource) {
        if (snapshot == null || message == null) {
            return null;
        }
        File destination = getArchiveFile(snapshot, message);
        if (destination == null) {
            return null;
        }
        LinkedHashSet<String> sourcePaths = new LinkedHashSet<>();
        addSource(sourcePaths, preferredSource, destination);
        FileLoader loader = FileLoader.getInstance(snapshot.account);
        addSource(sourcePaths, loader.getPathToMessageWithoutDeletedArchive(message, false, true), destination);
        addSource(sourcePaths, loader.getPathToMessageWithoutDeletedArchive(message, true, true), destination);
        // An outgoing attachPath can be stale after editing/resending. Prefer FileLoader's
        // canonical primary locations, while retaining attachPath as the final fallback.
        if (!TextUtils.isEmpty(message.attachPath)) {
            addSource(sourcePaths, new File(message.attachPath), destination);
        }
        if (sourcePaths.isEmpty()) {
            return null;
        }
        return new ArchiveRequest(snapshot, buildMessageKey(snapshot, message), destination,
                new ArrayList<>(sourcePaths), getExpectedPrimaryMediaSize(message),
                message.dialog_id, message.id);
    }

    private static long getExpectedPrimaryMediaSize(TLRPC.Message message) {
        long documentSize = MessageObject.getMessageSize(message);
        if (documentSize > 0) {
            return documentSize;
        }
        TLRPC.Photo photo = getPrimaryPhoto(message);
        if (photo == null) {
            return 0;
        }
        String primaryFileName = FileLoader.getMessageFileName(message);
        for (TLRPC.PhotoSize size : photo.sizes) {
            if (size != null && size.size > 0
                    && TextUtils.equals(primaryFileName, FileLoader.getAttachFileName(size))) {
                return size.size;
            }
        }
        return 0;
    }

    private static boolean isPrimaryPhotoVariant(TLRPC.Message message, String safeRequestedName) {
        TLRPC.Photo photo = getPrimaryPhoto(message);
        if (photo == null || TextUtils.isEmpty(safeRequestedName)) {
            return false;
        }
        for (TLRPC.PhotoSize size : photo.sizes) {
            if (size != null && safeRequestedName.equals(safeLeafName(FileLoader.getAttachFileName(size)))) {
                return true;
            }
        }
        return false;
    }

    private static TLRPC.Photo getPrimaryPhoto(TLRPC.Message message) {
        if (message == null) {
            return null;
        }
        if (message instanceof TLRPC.TL_messageService && message.action != null) {
            return message.action.photo;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return media.photo;
        }
        if (media instanceof TLRPC.TL_messageMediaWebPage && media.webpage != null
                && media.webpage.document == null) {
            return media.webpage.photo;
        }
        return null;
    }

    private static File getArchiveFile(ContainerSnapshot snapshot, TLRPC.Message message) {
        if (snapshot == null || message == null) {
            return null;
        }
        String fileName = safeLeafName(FileLoader.getMessageFileName(message));
        if (TextUtils.isEmpty(fileName)) {
            return null;
        }
        File messageDirectory = getMessageArchiveDirectory(snapshot, message.dialog_id, message.id);
        if (messageDirectory == null) {
            return null;
        }
        try {
            File destination = new File(messageDirectory, fileName).getCanonicalFile();
            String rootPath = snapshot.archiveRoot.getPath() + File.separator;
            if (!destination.getPath().startsWith(rootPath)) {
                return null;
            }
            return destination;
        } catch (IOException e) {
            FileLog.e("Unable to resolve archived media path", e);
            return null;
        }
    }

    private static File getDialogArchiveDirectory(ContainerSnapshot snapshot, long dialogId) {
        if (snapshot == null) {
            return null;
        }
        try {
            File directory = new File(snapshot.archiveRoot,
                    "d_" + Long.toUnsignedString(dialogId, 16)).getCanonicalFile();
            return snapshot.archiveRoot.equals(directory.getParentFile()) ? directory : null;
        } catch (IOException e) {
            FileLog.e("Unable to resolve deleted-media dialog directory", e);
            return null;
        }
    }

    private static File getMessageArchiveDirectory(ContainerSnapshot snapshot, long dialogId, int messageId) {
        File dialogDirectory = getDialogArchiveDirectory(snapshot, dialogId);
        if (dialogDirectory == null) {
            return null;
        }
        try {
            File directory = new File(dialogDirectory,
                    "m_" + Integer.toUnsignedString(messageId, 16)).getCanonicalFile();
            return dialogDirectory.equals(directory.getParentFile()) ? directory : null;
        } catch (IOException e) {
            FileLog.e("Unable to resolve deleted-media message directory", e);
            return null;
        }
    }

    private static void tombstoneArchiveDirectory(ContainerSnapshot snapshot, File directory) {
        if (snapshot == null || directory == null || !isCurrentContainer(snapshot)) {
            return;
        }
        String rootPath = canonicalPath(snapshot.archiveRoot);
        String targetPath = canonicalPath(directory);
        if (rootPath == null || targetPath == null || targetPath.equals(rootPath)
                || !targetPath.startsWith(rootPath + File.separator)) {
            FileLog.e("Refusing unsafe deleted-media archive purge " + directory);
            return;
        }
        File tombstone;
        synchronized (archiveIoLock) {
            if (!directory.exists() || !isCurrentContainer(snapshot)) {
                return;
            }
            File parent = directory.getParentFile();
            tombstone = new File(parent,
                    PURGE_PREFIX + UUID.randomUUID() + PURGE_SUFFIX);
            try {
                moveAtomically(directory, tombstone);
                if (!syncDirectory(parent)) {
                    FileLog.e("Unable to durably tombstone deleted-media archive " + directory);
                }
            } catch (Throwable e) {
                FileLog.e("Unable to tombstone deleted-media archive " + directory, e);
                archiveQueue.postRunnable(
                        () -> tombstoneArchiveDirectory(snapshot, directory),
                        RECONCILE_RETRY_DELAY_MS);
                return;
            }
        }
        File finalTombstone = tombstone;
        archiveQueue.postRunnable(() -> deleteArchiveTombstone(snapshot, finalTombstone));
    }

    private static void deleteArchiveTombstone(ContainerSnapshot snapshot, File tombstone) {
        if (!isArchivePurgeTombstone(tombstone == null ? null : tombstone.getName())) {
            return;
        }
        String rootPath = canonicalPath(snapshot.archiveRoot);
        String targetPath = canonicalPath(tombstone);
        if (rootPath == null || targetPath == null
                || !targetPath.startsWith(rootPath + File.separator)) {
            FileLog.e("Refusing unsafe deleted-media tombstone purge " + tombstone);
            return;
        }
        deleteRecursively(tombstone);
        File parent = tombstone.getParentFile();
        if (!tombstone.exists()) {
            cleanupEmptyParents(parent, snapshot.archiveRoot);
        }
        File existingParent = parent;
        while (existingParent != null && !existingParent.exists()) {
            existingParent = existingParent.getParentFile();
        }
        if (existingParent != null) {
            syncDirectory(existingParent);
        }
        if (tombstone.exists()) {
            archiveQueue.postRunnable(() -> deleteArchiveTombstone(snapshot, tombstone),
                    RECONCILE_RETRY_DELAY_MS);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) {
            FileLog.e("Unable to delete deleted-media archive path " + file);
        }
    }

    private static String buildMessageKey(ContainerSnapshot snapshot, TLRPC.Message message) {
        return snapshot.account + ":" + snapshot.containerId + ":" + message.dialog_id + ":" + message.id
                + ":" + safeLeafName(FileLoader.getMessageFileName(message));
    }

    private static long parseMessageKeyLong(String messageKey, int index) {
        if (TextUtils.isEmpty(messageKey)) {
            return 0;
        }
        String[] parts = messageKey.split(":", 5);
        if (parts.length <= index) {
            return 0;
        }
        try {
            return Long.parseLong(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void addSource(Set<String> sourcePaths, File source, File destination) {
        String sourcePath = canonicalPath(source);
        String destinationPath = canonicalPath(destination);
        if (sourcePath != null && !sourcePath.equals(destinationPath)) {
            sourcePaths.add(sourcePath);
        }
    }

    private static boolean mergeSourcesLocked(PendingEntry entry, Iterable<String> sourcePaths, boolean promote) {
        LinkedHashSet<String> previous = new LinkedHashSet<>(entry.sourcePaths);
        if (promote) {
            LinkedHashSet<String> reordered = new LinkedHashSet<>();
            for (String sourcePath : sourcePaths) {
                if (!TextUtils.isEmpty(sourcePath) && reordered.size() < MAX_SOURCE_PATHS) {
                    reordered.add(sourcePath);
                }
            }
            for (String sourcePath : entry.sourcePaths) {
                if (reordered.size() >= MAX_SOURCE_PATHS) {
                    break;
                }
                reordered.add(sourcePath);
            }
            entry.sourcePaths.clear();
            entry.sourcePaths.addAll(reordered);
        } else {
            for (String sourcePath : sourcePaths) {
                if (!TextUtils.isEmpty(sourcePath) && entry.sourcePaths.size() < MAX_SOURCE_PATHS) {
                    entry.sourcePaths.add(sourcePath);
                }
            }
        }
        boolean changed = !previous.equals(entry.sourcePaths);
        if (changed) {
            entry.sourceGeneration++;
            for (String oldPath : previous) {
                if (!entry.sourcePaths.contains(oldPath)) {
                    removePendingSourceIfUnreferencedLocked(oldPath, entry);
                    entry.cleanupRequestedSources.remove(oldPath);
                }
            }
        }
        pendingSources.addAll(entry.sourcePaths);
        return changed;
    }

    private static void enqueue(ArchiveRequest request) {
        synchronized (pendingLock) {
            PendingEntry entry = pendingMessages.get(request.messageKey);
            if (!matches(entry, request.snapshot) || entry.workerScheduled
                    || entry.lastAttemptedGeneration >= entry.sourceGeneration) {
                return;
            }
            entry.workerScheduled = true;
            entry.activeJobs = 1;
            entry.lastTouched = SystemClock.elapsedRealtime();
            entry.lastTouchedWallTime = System.currentTimeMillis();
        }
        archiveQueue.postRunnable(() -> runArchiveWorker(request.messageKey, request.snapshot));
    }

    private static void runArchiveWorker(String messageKey, ContainerSnapshot snapshot) {
        try {
            while (true) {
                ArchiveRequest request;
                long generation;
                synchronized (pendingLock) {
                    PendingEntry entry = pendingMessages.get(messageKey);
                    if (!matches(entry, snapshot)) {
                        return;
                    }
                    generation = entry.sourceGeneration;
                    entry.lastAttemptedGeneration = generation;
                    request = entry.toRequest();
                }
                copyToArchive(request);
                synchronized (pendingLock) {
                    PendingEntry entry = pendingMessages.get(messageKey);
                    if (!matches(entry, snapshot) || entry.sourceGeneration <= generation) {
                        return;
                    }
                    // A completion callback supplied a newer source during this attempt.
                }
            }
        } finally {
            ArchiveRequest reschedule = null;
            synchronized (pendingLock) {
                PendingEntry entry = pendingMessages.get(messageKey);
                if (matches(entry, snapshot)) {
                    entry.workerScheduled = false;
                    entry.activeJobs = 0;
                    if (entry.lastAttemptedGeneration < entry.sourceGeneration) {
                        reschedule = entry.toRequest();
                    }
                }
            }
            if (reschedule != null) {
                enqueue(reschedule);
            }
        }
    }

    private static void copyToArchive(ArchiveRequest request) {
        synchronized (archiveIoLock) {
            copyToArchiveLocked(request);
        }
    }

    private static void copyToArchiveLocked(ArchiveRequest request) {
        if (!isCurrentContainer(request.snapshot)) {
            forgetPending(request.messageKey);
            return;
        }
        if (isCompleteArchive(request.destination, request.expectedMediaSize)
                && syncDirectory(request.destination.getParentFile())) {
            completePending(request.messageKey);
            return;
        }
        File source = findReadableSource(request);
        if (source == null) {
            return;
        }
        File parent = request.destination.getParentFile();
        if (!request.snapshot.containerDirectory.isDirectory() || !isCurrentContainer(request.snapshot)) {
            forgetPending(request.messageKey);
            return;
        }
        cleanupStaleParts(request.snapshot.archiveRoot);
        if (!parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        if (!isCurrentContainer(request.snapshot)) {
            cleanupEmptyParents(parent, request.snapshot.archiveRoot);
            if (!isCurrentContainer(request.snapshot)) {
                forgetPending(request.messageKey);
            }
            return;
        }

        File temporary = new File(parent, ".agram-part-" + UUID.randomUUID() + ".tmp");
        boolean success = false;
        try {
            long expectedLength = source.length();
            if (request.expectedMediaSize > 0 && expectedLength != request.expectedMediaSize) {
                throw new IOException("Deleted-media source is incomplete: " + expectedLength
                        + " of " + request.expectedMediaSize + " bytes");
            }
            long copied = 0;
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    copied += read;
                }
                output.flush();
                output.getFD().sync();
            }
            if (copied != expectedLength || source.length() != expectedLength) {
                throw new IOException("Deleted-media source changed while it was copied");
            }
            if (!isCurrentContainer(request.snapshot)) {
                return;
            }
            moveAtomically(temporary, request.destination);
            if (!isCurrentContainer(request.snapshot)) {
                request.destination.delete();
                cleanupEmptyParents(parent, request.snapshot.archiveRoot);
                forgetPending(request.messageKey);
                return;
            }
            success = isCompleteArchive(request.destination, request.expectedMediaSize)
                    && syncDirectory(parent);
        } catch (Throwable e) {
            FileLog.e("Unable to archive deleted-message media " + request.destination, e);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                FileLog.e("Unable to remove incomplete deleted-media archive " + temporary);
            }
        }
        if (success) {
            completePending(request.messageKey);
        }
    }

    private static File findReadableSource(ArchiveRequest request) {
        for (String path : request.sourcePaths) {
            File source = new File(path);
            long length = source.length();
            if (source.isFile() && source.canRead() && length > 0
                    && (request.expectedMediaSize <= 0 || length == request.expectedMediaSize)) {
                return source;
            }
        }
        return null;
    }

    private static void moveAtomically(File source, File destination) throws IOException {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Directory-entry durability is part of the archive/journal commit protocol. */
    private static boolean syncDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return false;
        }
        FileDescriptor descriptor = null;
        try {
            // Android's public OsConstants does not expose O_DIRECTORY on every compile SDK;
            // the path was verified with isDirectory() immediately above.
            descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            Os.fsync(descriptor);
            return true;
        } catch (ErrnoException | RuntimeException e) {
            FileLog.e("Unable to fsync deleted-media directory " + directory, e);
            return false;
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException e) {
                    FileLog.e("Unable to close deleted-media directory " + directory, e);
                }
            }
        }
    }

    private static void cleanupStaleParts(File archiveRoot) {
        String rootPath = canonicalPath(archiveRoot);
        if (rootPath == null) {
            return;
        }
        synchronized (pendingLock) {
            if (!cleanedArchiveRoots.add(rootPath)) {
                return;
            }
        }
        synchronized (archiveIoLock) {
            cleanupStalePartsRecursive(archiveRoot);
        }
    }

    private static void cleanupStalePartsRecursive(File file) {
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (isArchivePurgeTombstone(child.getName())) {
                    deleteRecursively(child);
                    syncDirectory(child.getParentFile());
                } else {
                    cleanupStalePartsRecursive(child);
                }
            } else if (isArchiveTemporaryFile(child.getName()) && !child.delete()) {
                FileLog.e("Unable to remove stale deleted-media archive " + child);
            }
        }
    }

    private static boolean isArchivePurgeTombstone(String name) {
        if (name == null || !name.startsWith(PURGE_PREFIX) || !name.endsWith(PURGE_SUFFIX)) {
            return false;
        }
        String uuid = name.substring(PURGE_PREFIX.length(), name.length() - PURGE_SUFFIX.length());
        try {
            return TextUtils.equals(UUID.fromString(uuid).toString(), uuid);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isArchiveTemporaryFile(String name) {
        if (name == null || !name.endsWith(".tmp")) {
            return false;
        }
        String archivePrefix = ".agram-part-";
        String journalPrefix = ".agram-journal-part-";
        String prefix;
        if (name.startsWith(archivePrefix)) {
            prefix = archivePrefix;
        } else if (name.startsWith(journalPrefix)) {
            prefix = journalPrefix;
        } else {
            return false;
        }
        String uuid = name.substring(prefix.length(), name.length() - 4);
        try {
            return TextUtils.equals(UUID.fromString(uuid).toString(), uuid);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isCurrentContainer(ContainerSnapshot snapshot) {
        synchronized (pendingLock) {
            if (isInvalidatedLocked(snapshot)) {
                return false;
            }
        }
        try {
            AgramContainerManager.ContainerRecord current = AgramContainerManager.getInstance().getContainer(snapshot.account);
            return current != null && current.isStorageAccessible() && snapshot.containerId.equals(current.id);
        } catch (Throwable e) {
            FileLog.e("Unable to validate Agram container for deleted-media archive", e);
            return false;
        }
    }

    private static boolean isCompleteArchive(File file, long expectedMediaSize) {
        if (file == null || !file.isFile() || file.length() <= 0) {
            return false;
        }
        return expectedMediaSize <= 0 || file.length() == expectedMediaSize;
    }

    private static void forgetPending(String messageKey) {
        synchronized (pendingLock) {
            forgetPendingLocked(messageKey);
        }
    }

    private static void completePending(String messageKey) {
        synchronized (pendingLock) {
            PendingEntry removed = forgetPendingLocked(messageKey);
            if (removed != null) {
                for (String path : removed.cleanupRequestedSources) {
                    if (!pendingSources.contains(path) && isManagedMediaPath(path)) {
                        File source = new File(path);
                        try {
                            if (source.isFile() && !source.delete()) {
                                FileLog.e("Unable to finish deferred cache cleanup for " + source);
                            }
                        } catch (Throwable e) {
                            FileLog.e("Unable to finish deferred cache cleanup for " + source, e);
                        }
                    }
                }
            }
        }
    }

    private static PendingEntry forgetPendingLocked(String messageKey) {
        PendingEntry removed = pendingMessages.remove(messageKey);
        if (removed == null) {
            return null;
        }
        File journalFile = getJournalFile(removed);
        if (journalFile != null && journalFile.exists()) {
            if (!journalFile.delete()) {
                FileLog.e("Unable to remove completed deleted-media journal " + journalFile);
            } else {
                syncDirectory(journalFile.getParentFile());
            }
        }
        for (String path : removed.sourcePaths) {
            boolean stillPending = false;
            for (PendingEntry other : pendingMessages.values()) {
                if (other.sourcePaths.contains(path)) {
                    stillPending = true;
                    break;
                }
            }
            if (!stillPending) {
                pendingSources.remove(path);
            }
        }
        return removed;
    }

    /** Must only be called while pendingLock is held. */
    private static void removePendingSourceIfUnreferencedLocked(String path, PendingEntry excluding) {
        for (PendingEntry other : pendingMessages.values()) {
            if (other != excluding && other.sourcePaths.contains(path)) {
                return;
            }
        }
        pendingSources.remove(path);
    }

    private static boolean matches(PendingEntry entry, ContainerSnapshot snapshot) {
        return entry != null && snapshot != null
                && entry.snapshot.account == snapshot.account
                && TextUtils.equals(entry.snapshot.containerId, snapshot.containerId);
    }

    private static String containerIdentity(int account, String containerId) {
        return account + ":" + containerId;
    }

    private static String messageGuardKey(ContainerSnapshot snapshot, long dialogId, int messageId) {
        return containerIdentity(snapshot.account, snapshot.containerId) + ":m:" + dialogId + ":" + messageId;
    }

    /** Must only be called while pendingLock is held. */
    private static boolean isPurgedLocked(ContainerSnapshot snapshot, long dialogId, int messageId) {
        return snapshot == null || purgedMessageGuards.contains(
                messageGuardKey(snapshot, dialogId, messageId));
    }

    /** Must only be called while pendingLock is held. */
    private static void addPurgedMessageGuardLocked(ContainerSnapshot snapshot,
                                                     long dialogId, int messageId) {
        String key = messageGuardKey(snapshot, dialogId, messageId);
        purgedMessageGuards.remove(key);
        purgedMessageGuards.add(key);
        trimOldestLocked(purgedMessageGuards, MAX_PURGED_MESSAGE_GUARDS);
    }

    /** Must only be called while pendingLock is held. */
    private static void trimOldestLocked(Set<String> values, int maximumSize) {
        while (values.size() > maximumSize) {
            Iterator<String> iterator = values.iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    /** Must only be called while pendingLock is held. */
    private static boolean isInvalidatedLocked(ContainerSnapshot snapshot) {
        return snapshot == null || invalidatedContainers.contains(
                containerIdentity(snapshot.account, snapshot.containerId));
    }

    private static void prunePendingLocked() {
        if (pendingMessages.isEmpty()) {
            return;
        }
        ArrayList<String> expired = new ArrayList<>();
        for (PendingEntry entry : pendingMessages.values()) {
            if (isExpired(entry)) {
                expired.add(entry.messageKey);
            }
        }
        for (String messageKey : expired) {
            forgetPendingLocked(messageKey);
        }
    }

    private static boolean isExpired(PendingEntry entry) {
        long now = System.currentTimeMillis();
        return entry != null && !entry.committed && !entry.reconciling
                && !entry.downloadInFlight
                && entry.activeJobs == 0 && entry.prepareTokens.isEmpty()
                && entry.lastTouchedWallTime > 0 && now >= entry.lastTouchedWallTime
                && now - entry.lastTouchedWallTime > PENDING_TTL_MS;
    }

    private static void cleanupEmptyParents(File directory, File stopExclusive) {
        File current = directory;
        while (current != null && !current.equals(stopExclusive)) {
            File[] children = current.listFiles();
            if (children != null && children.length != 0) {
                return;
            }
            if (!current.delete() && current.exists()) {
                return;
            }
            current = current.getParentFile();
        }
    }

    private static String canonicalPath(File file) {
        if (file == null || TextUtils.isEmpty(file.getPath())) {
            return null;
        }
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static boolean isManagedMediaPath(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        int[] types = {
                FileLoader.MEDIA_DIR_IMAGE, FileLoader.MEDIA_DIR_AUDIO,
                FileLoader.MEDIA_DIR_VIDEO, FileLoader.MEDIA_DIR_DOCUMENT,
                FileLoader.MEDIA_DIR_CACHE, FileLoader.MEDIA_DIR_FILES,
                FileLoader.MEDIA_DIR_STORIES, FileLoader.MEDIA_DIR_IMAGE_PUBLIC,
                FileLoader.MEDIA_DIR_VIDEO_PUBLIC
        };
        for (int type : types) {
            String root = canonicalPath(FileLoader.checkDirectory(type));
            if (root != null && (path.equals(root) || path.startsWith(root + File.separator))) {
                return true;
            }
        }
        return false;
    }

    private static String safeLeafName(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 180));
        for (int i = 0; i < value.length() && safe.length() < 180; i++) {
            char character = value.charAt(i);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-') {
                safe.append(character);
            } else {
                safe.append('_');
            }
        }
        while (safe.length() > 0 && safe.charAt(0) == '.') {
            safe.deleteCharAt(0);
        }
        return safe.toString();
    }

    private static final class ContainerSnapshot {
        final int account;
        final String containerId;
        final File containersRoot;
        final File containerDirectory;
        final File archiveRoot;
        final File journalRoot;

        ContainerSnapshot(int account, String containerId, File containersRoot,
                          File containerDirectory, File archiveRoot, File journalRoot) {
            this.account = account;
            this.containerId = containerId;
            this.containersRoot = containersRoot;
            this.containerDirectory = containerDirectory;
            this.archiveRoot = archiveRoot;
            this.journalRoot = journalRoot;
        }
    }

    private static final class ArchiveRequest {
        final ContainerSnapshot snapshot;
        final String messageKey;
        final File destination;
        final ArrayList<String> sourcePaths;
        final long expectedMediaSize;
        final long dialogId;
        final int messageId;

        ArchiveRequest(ContainerSnapshot snapshot, String messageKey, File destination,
                       ArrayList<String> sourcePaths, long expectedMediaSize,
                       long dialogId, int messageId) {
            this.snapshot = snapshot;
            this.messageKey = messageKey;
            this.destination = destination;
            this.sourcePaths = sourcePaths;
            this.expectedMediaSize = expectedMediaSize;
            this.dialogId = dialogId;
            this.messageId = messageId;
        }
    }

    private static final class ArchivedMessagePath {
        final long dialogId;
        final int messageId;

        ArchivedMessagePath(long dialogId, int messageId) {
            this.dialogId = dialogId;
            this.messageId = messageId;
        }
    }

    private static final class PendingEntry {
        final String messageKey;
        final ContainerSnapshot snapshot;
        final File destination;
        final long expectedMediaSize;
        final long dialogId;
        final int messageId;
        final LinkedHashSet<String> sourcePaths = new LinkedHashSet<>();
        final LinkedHashSet<String> cleanupRequestedSources = new LinkedHashSet<>();
        final Set<String> prepareTokens = new HashSet<>();
        boolean committed;
        boolean downloadFailed;
        boolean downloadInFlight;
        boolean reconciling;
        boolean workerScheduled;
        boolean readableSourceObserved;
        long lastTouched;
        long lastTouchedWallTime;
        long sourceGeneration = 1;
        long lastAttemptedGeneration;
        int activeJobs;

        PendingEntry(ArchiveRequest request) {
            messageKey = request.messageKey;
            snapshot = request.snapshot;
            destination = request.destination;
            expectedMediaSize = request.expectedMediaSize;
            dialogId = request.dialogId;
            messageId = request.messageId;
            for (String sourcePath : request.sourcePaths) {
                if (sourcePaths.size() >= MAX_SOURCE_PATHS) {
                    break;
                }
                sourcePaths.add(sourcePath);
            }
            lastTouched = SystemClock.elapsedRealtime();
            lastTouchedWallTime = System.currentTimeMillis();
        }

        ArchiveRequest toRequest() {
            return new ArchiveRequest(snapshot, messageKey, destination,
                    new ArrayList<>(sourcePaths), expectedMediaSize, dialogId, messageId);
        }
    }

    private static final class PendingReconcile {
        final String messageKey;
        final ContainerSnapshot snapshot;
        final long dialogId;
        final int messageId;

        PendingReconcile(String messageKey, ContainerSnapshot snapshot, long dialogId, int messageId) {
            this.messageKey = messageKey;
            this.snapshot = snapshot;
            this.dialogId = dialogId;
            this.messageId = messageId;
        }
    }

    public static final class PreparedArchive {
        private final String messageKey;
        private final String tokenId;
        private final ContainerSnapshot snapshot;

        private PreparedArchive(String messageKey, String tokenId, ContainerSnapshot snapshot) {
            this.messageKey = messageKey;
            this.tokenId = tokenId;
            this.snapshot = snapshot;
        }
    }

    public static final class DownloadToken {
        private final ContainerSnapshot snapshot;

        private DownloadToken(ContainerSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }
}
