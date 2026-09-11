/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a self-contained HTML ZIP or PDF export for an ordinary Telegram cloud dialog.
 *
 * <p>The exporter deliberately does not use Telegram's in-memory message list: large dialogs are
 * paged from the API into a small temporary SQLite database and streamed from there into HTML.
 * The local Agram deletion snapshot is merged last, so a locally retained ordinary message wins
 * over a server copy with the same id. Both output formats use the same staged HTML and locally
 * available media. Secret chats, disappearing/view-once media and protected
 * content are rejected both at the dialog boundary and again for every message.</p>
 */
public final class AgramChatExportManager {

    private static final int PAGE_SIZE = 100;
    private static final int COPY_BUFFER_SIZE = 128 * 1024;
    private static final Set<String> RETIRED_CONTAINERS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final DispatchQueue CACHE_QUEUE = new DispatchQueue("agramExportCache");

    private AgramChatExportManager() {
    }

    public enum Format {
        HTML_ZIP,
        PDF
    }

    public interface Listener {
        void onProgress(int processed, int total, String stage);

        void onDone(File archive, int messageCount, int deletedCount);

        void onError(String message);
    }

    public static final class ExportTask {
        private final Worker worker;

        private ExportTask(Worker worker) {
            this.worker = worker;
        }

        public void cancel() {
            worker.cancel();
        }

        public boolean isCancelled() {
            return worker.cancelled.get();
        }
    }

    /**
     * @param senderId 0 for the whole chat/private dialog, or a peer dialog id (positive user,
     *                 negative sender-chat/channel) to export only that participant.
     * @param minDateSec inclusive Unix timestamp, or 0 for no lower boundary.
     * @param maxDateSec inclusive Unix timestamp, or 0 for no upper boundary.
     */
    public static ExportTask start(int account, long dialogId, String title, long senderId,
                                   int minDateSec, int maxDateSec, Listener listener) {
        return start(account, dialogId, 0, title, senderId, minDateSec, maxDateSec,
                Format.HTML_ZIP, listener);
    }

    public static ExportTask start(int account, long dialogId, String title, long senderId,
                                   int minDateSec, int maxDateSec, Format format,
                                   Listener listener) {
        return start(account, dialogId, 0, title, senderId, minDateSec, maxDateSec,
                format, listener);
    }

    /**
     * Starts an export which can span a current supergroup and its migrated-from basic group.
     * The current dialog is always processed first, then {@code mergeDialogId}.
     */
    public static ExportTask start(int account, long dialogId, long mergeDialogId, String title,
                                   long senderId, int minDateSec, int maxDateSec,
                                   Listener listener) {
        return start(account, dialogId, mergeDialogId, title, senderId, minDateSec, maxDateSec,
                Format.HTML_ZIP, listener);
    }

    public static ExportTask start(int account, long dialogId, long mergeDialogId, String title,
                                   long senderId, int minDateSec, int maxDateSec, Format format,
                                   Listener listener) {
        long clientUserId = UserConfig.getInstance(account).getClientUserId();
        AgramContainerManager.ContainerRecord container =
                AgramContainerManager.getInstance().getContainer(account);
        String containerId = container == null ? "" : container.id;
        Worker worker = new Worker(account, dialogId, mergeDialogId, title, senderId,
                Math.max(0, minDateSec), Math.max(0, maxDateSec), clientUserId,
                containerId, format == null ? Format.HTML_ZIP : format, listener);
        worker.exportQueue.postRunnable(worker::start);
        return new ExportTask(worker);
    }

    /** Removes plaintext, share-ready exports that belong to a deleted account container. */
    public static void purgeContainerCache(String containerId) {
        if (TextUtils.isEmpty(containerId) || !TextUtils.equals(containerId, safeFileName(containerId))) {
            return;
        }
        RETIRED_CONTAINERS.add(containerId);
        File root = new File(ApplicationLoader.applicationContext.getCacheDir(),
                "agram_chat_exports");
        File target = new File(root, containerId);
        CACHE_QUEUE.postRunnable(() -> deleteRecursively(target));
    }

    private static final class Worker {
        private final int account;
        private final long[] sourceDialogIds;
        private final String title;
        private final long senderId;
        private final int minDateSec;
        private final int maxDateSec;
        private final long clientUserId;
        private final String containerId;
        private final Format format;
        private final Listener listener;
        private final DispatchQueue exportQueue;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final Map<Long, TLRPC.User> users = new HashMap<>();
        private final Map<Long, TLRPC.Chat> chats = new HashMap<>();

        private File stageDirectory;
        private File databaseFile;
        private File outputFile;
        private SQLiteDatabase database;
        private volatile int currentRequestId;
        private volatile AgramHtmlPdfRenderer.RenderTask pdfRenderTask;
        private int networkSourceIndex;
        private int offsetId;
        private int fetchedCount;
        private int expectedCount;
        private int previousOffsetId = -1;
        private final int[] sourceExpectedCounts;
        private int deletedSourceIndex;
        private MessagesStorage.AgramExportPageCursor deletedCursor;
        private int deletedProcessedCount;

        private Worker(int account, long dialogId, long mergeDialogId, String title, long senderId,
                       int minDateSec, int maxDateSec, long clientUserId,
                       String containerId, Format format, Listener listener) {
            this.account = account;
            this.sourceDialogIds = mergeDialogId != 0 && mergeDialogId != dialogId
                    ? new long[]{dialogId, mergeDialogId}
                    : new long[]{dialogId};
            this.title = TextUtils.isEmpty(title) ? "Telegram" : title;
            this.senderId = senderId;
            this.minDateSec = minDateSec;
            this.maxDateSec = maxDateSec;
            this.clientUserId = clientUserId;
            this.containerId = containerId;
            this.format = format;
            this.listener = listener;
            this.sourceExpectedCounts = new int[sourceDialogIds.length];
            this.exportQueue = new DispatchQueue("agramChatExport-" + UUID.randomUUID());
        }

        private void start() {
            if (cancelled.get()) {
                finishCancelled();
                return;
            }
            if (!isSessionValid()) {
                finishError("Сессия или контейнер недоступны");
                return;
            }
            if (!areSourceDialogsExportable()) {
                finishError("Экспорт защищённого контента недоступен");
                return;
            }
            if (minDateSec != 0 && maxDateSec != 0 && minDateSec > maxDateSec) {
                finishError("Начальная дата позже конечной");
                return;
            }

            try {
                File root = new File(ApplicationLoader.applicationContext.getCacheDir(),
                        "agram_chat_exports");
                if (!root.exists() && !root.mkdirs()) {
                    throw new IOException("Unable to create export cache directory");
                }
                File containerRoot = new File(root, safeFileName(containerId));
                ensureDirectory(containerRoot);
                stageDirectory = new File(containerRoot, "work-" + UUID.randomUUID());
                ensureDirectory(stageDirectory);
                ensureDirectory(new File(stageDirectory, "css"));
                ensureDirectory(new File(stageDirectory, "js"));
                ensureDirectory(new File(stageDirectory, "photos"));
                ensureDirectory(new File(stageDirectory, "video_files"));
                ensureDirectory(new File(stageDirectory, "files"));
                ensureDirectory(new File(stageDirectory, "stickers"));

                copyAsset("agram_export/style.css", new File(stageDirectory, "css/style.css"));
                copyAsset("agram_export/script.js", new File(stageDirectory, "js/script.js"));

                databaseFile = new File(stageDirectory, ".messages.sqlite");
                database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
                database.execSQL("PRAGMA journal_mode=MEMORY");
                database.execSQL("PRAGMA synchronous=OFF");
                database.execSQL("CREATE TABLE export_messages ("
                        + "source_dialog_id INTEGER NOT NULL,"
                        + "mid INTEGER NOT NULL,"
                        + "date INTEGER NOT NULL,"
                        + "sender_id INTEGER NOT NULL,"
                        + "sender_name TEXT NOT NULL,"
                        + "username TEXT,"
                        + "sender_deleted INTEGER NOT NULL DEFAULT 0,"
                        + "body TEXT,"
                        + "reply_mid INTEGER NOT NULL DEFAULT 0,"
                        + "media_kind TEXT,"
                        + "media_rel TEXT,"
                        + "media_name TEXT,"
                        + "media_meta TEXT,"
                        + "reactions_html TEXT,"
                        + "deleted INTEGER NOT NULL DEFAULT 0,"
                        + "PRIMARY KEY(source_dialog_id, mid)"
                        + ")");
                database.execSQL("CREATE INDEX export_messages_date_idx "
                        + "ON export_messages(date, source_dialog_id, mid)");
            } catch (Throwable e) {
                if (cancelled.get()) {
                    finishCancelled();
                    return;
                }
                FileLog.e("Unable to prepare Agram chat export", e);
                finishError("Не удалось подготовить экспорт");
                return;
            }

            notifyProgress(0, 0, LocaleController.getString(R.string.AGramExportStageHistory));
            requestNextPage();
        }

        private void requestNextPage() {
            if (cancelled.get()) {
                finishCancelled();
                return;
            }
            if (!isSessionValid()) {
                finishError("Сессия или контейнер изменились во время экспорта");
                return;
            }
            if (!areSourceDialogsExportable()) {
                finishError("Защита контента была включена во время экспорта");
                return;
            }
            if (networkSourceIndex >= sourceDialogIds.length) {
                beginDeletedPages();
                return;
            }

            final long sourceDialogId = sourceDialogIds[networkSourceIndex];
            final TLObject request;
            try {
                TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(sourceDialogId);
                if (peer == null) {
                    throw new IllegalStateException("Input peer is unavailable");
                }
                if (senderId != 0) {
                    TLRPC.TL_messages_search search = new TLRPC.TL_messages_search();
                    search.peer = peer;
                    search.q = "";
                    search.from_id = MessagesController.getInstance(account).getInputPeer(senderId);
                    search.saved_reaction = null;
                    search.filter = new TLRPC.TL_inputMessagesFilterEmpty();
                    search.min_date = minDateSec;
                    search.max_date = maxDateSec;
                    search.offset_id = offsetId;
                    search.add_offset = 0;
                    search.limit = PAGE_SIZE;
                    search.max_id = 0;
                    search.min_id = 0;
                    search.hash = 0;
                    request = search;
                } else {
                    TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
                    history.peer = peer;
                    history.offset_id = offsetId;
                    history.offset_date = offsetId == 0 ? maxDateSec : 0;
                    history.add_offset = 0;
                    history.limit = PAGE_SIZE;
                    history.max_id = 0;
                    history.min_id = 0;
                    history.hash = 0;
                    request = history;
                }
            } catch (Throwable e) {
                FileLog.e("Unable to create Agram export history request", e);
                finishError("Не удалось открыть чат для экспорта");
                return;
            }

            currentRequestId = ConnectionsManager.getInstance(account).sendRequest(request,
                    (response, error) -> exportQueue.postRunnable(() -> {
                        currentRequestId = 0;
                        if (completed.get()) {
                            return;
                        }
                        if (cancelled.get()) {
                            finishCancelled();
                            return;
                        }
                        if (error != null) {
                            String detail = TextUtils.isEmpty(error.text) ? "NETWORK_ERROR" : error.text;
                            finishError("Не удалось загрузить историю: " + detail);
                            return;
                        }
                        if (!(response instanceof TLRPC.messages_Messages)) {
                            finishError("Telegram вернул неизвестный формат истории");
                            return;
                        }
                        processPage(sourceDialogId, (TLRPC.messages_Messages) response);
                    }));
        }

        private void processPage(long sourceDialogId, TLRPC.messages_Messages result) {
            try {
                if (!isSessionValid()) {
                    finishError("Сессия или контейнер изменились во время экспорта");
                    return;
                }
                if (!areSourceDialogsExportable()) {
                    finishError("Защита контента была включена во время экспорта");
                    return;
                }
                if (networkSourceIndex >= sourceDialogIds.length
                        || sourceDialogIds[networkSourceIndex] != sourceDialogId) {
                    finishError("Источник истории изменился во время экспорта");
                    return;
                }
                // Sender identity is materialized into SQLite while this page is processed.
                // Keeping every participant from a very large group would defeat bounded
                // message paging, so only retain the current response's peer dictionary.
                users.clear();
                chats.clear();
                cachePeers(result.users, result.chats);
                if (result.count > 0) {
                    sourceExpectedCounts[networkSourceIndex] = Math.max(
                            sourceExpectedCounts[networkSourceIndex], result.count);
                    expectedCount = 0;
                    for (int count : sourceExpectedCounts) {
                        expectedCount += count;
                    }
                }

                int nextOffset = 0;
                int oldestDate = Integer.MAX_VALUE;
                database.beginTransaction();
                try {
                    int resultSize = result.messages == null ? 0 : result.messages.size();
                    for (int i = 0; i < resultSize; i++) {
                        if (cancelled.get()) {
                            throw new ExportCancelledException();
                        }
                        TLRPC.Message message = result.messages.get(i);
                        if (message == null) {
                            continue;
                        }
                        if (message.id > 0 && (nextOffset == 0 || message.id < nextOffset)) {
                            nextOffset = message.id;
                        }
                        oldestDate = Math.min(oldestDate, message.date);
                        if (!withinDateRange(message.date) || !matchesSender(message, sourceDialogId)
                                || !isMessageExportable(message, sourceDialogId)) {
                            continue;
                        }
                        insertMessage(sourceDialogId, message, false);
                    }
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }

                int resultSize = result.messages == null ? 0 : result.messages.size();
                fetchedCount += resultSize;
                notifyProgress(fetchedCount, expectedCount,
                        LocaleController.getString(R.string.AGramExportStageHistory));

                boolean belowLowerBound = minDateSec != 0
                        && oldestDate != Integer.MAX_VALUE && oldestDate < minDateSec;
                boolean exhausted = resultSize == 0 || resultSize < PAGE_SIZE;
                boolean stalled = nextOffset == 0 || nextOffset == offsetId
                        || nextOffset == previousOffsetId;
                if (belowLowerBound || exhausted || stalled) {
                    advanceNetworkSource();
                } else {
                    previousOffsetId = offsetId;
                    offsetId = nextOffset;
                    requestNextPage();
                }
            } catch (ExportCancelledException e) {
                finishCancelled();
            } catch (Throwable e) {
                FileLog.e("Unable to process Agram export history page", e);
                finishError("Не удалось обработать историю чата");
            }
        }

        private void advanceNetworkSource() {
            networkSourceIndex++;
            offsetId = 0;
            previousOffsetId = -1;
            if (networkSourceIndex < sourceDialogIds.length) {
                notifyProgress(fetchedCount, expectedCount,
                        LocaleController.getString(R.string.AGramExportStageHistory));
                requestNextPage();
            } else {
                beginDeletedPages();
            }
        }

        private void beginDeletedPages() {
            deletedSourceIndex = 0;
            deletedCursor = null;
            deletedProcessedCount = 0;
            requestNextDeletedPage();
        }

        private void requestNextDeletedPage() {
            if (cancelled.get()) {
                finishCancelled();
                return;
            }
            if (!isSessionValid()) {
                finishError("Сессия или контейнер изменились во время экспорта");
                return;
            }
            if (!areSourceDialogsExportable()) {
                finishError("Защита контента была включена во время экспорта");
                return;
            }
            if (deletedSourceIndex >= sourceDialogIds.length) {
                createOutput();
                return;
            }

            final long sourceDialogId = sourceDialogIds[deletedSourceIndex];
            final MessagesStorage.AgramExportPageCursor requestedCursor = deletedCursor;
            notifyProgress(fetchedCount + deletedProcessedCount, expectedCount,
                    LocaleController.getString(R.string.AGramExportStageDeleted));
            try {
                MessagesStorage.getInstance(account).getAgramDeletedMessagesForExportPage(
                        sourceDialogId, minDateSec, maxDateSec, senderId, requestedCursor, PAGE_SIZE,
                        page -> exportQueue.postRunnable(() ->
                                processDeletedPage(sourceDialogId, requestedCursor, page)));
            } catch (Throwable e) {
                FileLog.e("Unable to request Agram deleted export snapshot", e);
                finishError("Не удалось прочитать локальный архив удалённых сообщений");
            }
        }

        private void processDeletedPage(long sourceDialogId,
                                        MessagesStorage.AgramExportPageCursor requestedCursor,
                                        MessagesStorage.AgramExportPage page) {
            if (completed.get()) {
                return;
            }
            if (cancelled.get()) {
                finishCancelled();
                return;
            }
            try {
                if (!isSessionValid()) {
                    finishError("Сессия или контейнер изменились во время экспорта");
                    return;
                }
                if (!areSourceDialogsExportable()) {
                    finishError("Защита контента была включена во время экспорта");
                    return;
                }
                if (page == null || deletedSourceIndex >= sourceDialogIds.length
                        || sourceDialogIds[deletedSourceIndex] != sourceDialogId
                        || !sameCursor(deletedCursor, requestedCursor)) {
                    finishError("Локальный архив вернул некорректную страницу");
                    return;
                }
                if (page.hasError()) {
                    FileLog.e("Unable to read an Agram deleted export page: " + page.error);
                    finishError(page.error);
                    return;
                }
                MessagesStorage.AgramExportSnapshot snapshot = page.snapshot;
                if (snapshot != null) {
                    users.clear();
                    chats.clear();
                    cachePeers(snapshot.users, snapshot.chats);
                    database.beginTransaction();
                    try {
                        for (int i = 0; i < snapshot.messages.size(); i++) {
                            if (cancelled.get()) {
                                throw new ExportCancelledException();
                            }
                            TLRPC.Message message = snapshot.messages.get(i);
                            if (message == null || !message.agramDeletedOnServer
                                    || !withinDateRange(message.date) || !matchesSender(message, sourceDialogId)
                                    || !isMessageExportable(message, sourceDialogId)) {
                                continue;
                            }
                            insertMessage(sourceDialogId, message, true);
                        }
                        database.setTransactionSuccessful();
                    } finally {
                        database.endTransaction();
                    }
                    deletedProcessedCount += snapshot.messages.size();
                }

                if (page.finished) {
                    deletedSourceIndex++;
                    deletedCursor = null;
                    requestNextDeletedPage();
                } else {
                    if (page.nextCursor == null || sameCursor(requestedCursor, page.nextCursor)) {
                        finishError("Локальный архив не смог продолжить выгрузку");
                        return;
                    }
                    // Request the next page only after this page's transaction and media copies
                    // are complete. This is deliberate backpressure on the storage queue.
                    deletedCursor = page.nextCursor;
                    requestNextDeletedPage();
                }
            } catch (ExportCancelledException e) {
                finishCancelled();
            } catch (Throwable e) {
                FileLog.e("Unable to merge deleted Agram messages", e);
                finishError("Не удалось добавить удалённые сообщения");
            }
        }

        private boolean sameCursor(MessagesStorage.AgramExportPageCursor first,
                                   MessagesStorage.AgramExportPageCursor second) {
            if (first == second) {
                return true;
            }
            return first != null && second != null
                    && first.tableIndex == second.tableIndex
                    && first.afterDate == second.afterDate
                    && first.afterMessageId == second.afterMessageId;
        }

        private boolean withinDateRange(int date) {
            return (minDateSec == 0 || date >= minDateSec)
                    && (maxDateSec == 0 || date <= maxDateSec);
        }

        private boolean matchesSender(TLRPC.Message message, long sourceDialogId) {
            return senderId == 0 || resolveSenderId(message, sourceDialogId) == senderId;
        }

        private boolean isMessageExportable(TLRPC.Message message, long sourceDialogId) {
            return MessageObject.canKeepDeletedOnServer(message, sourceDialogId)
                    && !MessagesController.getInstance(account).isPeerNoForwards(sourceDialogId);
        }

        private void cachePeers(ArrayList<TLRPC.User> pageUsers, ArrayList<TLRPC.Chat> pageChats) {
            if (pageUsers != null) {
                for (int i = 0; i < pageUsers.size(); i++) {
                    TLRPC.User user = pageUsers.get(i);
                    if (user != null) {
                        users.put(user.id, user);
                    }
                }
            }
            if (pageChats != null) {
                for (int i = 0; i < pageChats.size(); i++) {
                    TLRPC.Chat chat = pageChats.get(i);
                    if (chat != null) {
                        chats.put(chat.id, chat);
                    }
                }
            }
        }

        private void insertMessage(long sourceDialogId, TLRPC.Message message, boolean deleted)
                throws IOException {
            message.dialog_id = sourceDialogId;
            long fromId = resolveSenderId(message, sourceDialogId);
            SenderInfo sender = resolveSender(fromId);
            MediaInfo media = copyLocalMedia(sourceDialogId, message);

            String body = message.message == null ? "" : message.message;
            if (TextUtils.isEmpty(body) && message instanceof TLRPC.TL_messageService) {
                body = "[Сервисное сообщение]";
            }

            ContentValues values = new ContentValues();
            values.put("source_dialog_id", sourceDialogId);
            values.put("mid", message.id);
            values.put("date", message.date);
            values.put("sender_id", fromId);
            values.put("sender_name", sender.name);
            values.put("username", sender.username);
            values.put("sender_deleted", sender.deleted ? 1 : 0);
            values.put("body", body);
            values.put("reply_mid", message.reply_to == null ? 0 : message.reply_to.reply_to_msg_id);
            values.put("media_kind", media.kind);
            values.put("media_rel", media.relativePath);
            values.put("media_name", media.displayName);
            values.put("media_meta", media.metadata);
            values.put("reactions_html", renderReactions(message));
            values.put("deleted", deleted || message.agramDeletedOnServer ? 1 : 0);
            long inserted = database.insertWithOnConflict("export_messages", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            if (inserted == -1) {
                throw new IOException("Unable to store message " + message.id + " in export database");
            }
        }

        private long resolveSenderId(TLRPC.Message message, long sourceDialogId) {
            long id = MessageObject.getPeerId(message.from_id);
            if (id != 0) {
                return id;
            }
            if (message.out) {
                return UserConfig.getInstance(account).getClientUserId();
            }
            if (sourceDialogId > 0) {
                return sourceDialogId;
            }
            return sourceDialogId;
        }

        private SenderInfo resolveSender(long peerId) {
            if (peerId > 0) {
                TLRPC.User user = users.get(peerId);
                if (user == null) {
                    user = MessagesController.getInstance(account).getUser(peerId);
                }
                if (user == null) {
                    return new SenderInfo("Удалённый аккаунт · ID " + peerId, "", true);
                }
                boolean deleted = UserObject.isDeleted(user);
                String originalName = ContactsController.formatName(user.first_name, user.last_name);
                String username = UserObject.getPublicUsername(user);
                if (deleted) {
                    String name = TextUtils.isEmpty(originalName)
                            ? "Удалённый аккаунт"
                            : originalName + " · удалённый аккаунт";
                    return new SenderInfo(name + " · ID " + peerId, username, true);
                }
                if (TextUtils.isEmpty(originalName)) {
                    originalName = "Пользователь " + peerId;
                }
                return new SenderInfo(originalName, username, false);
            }

            long chatId = -peerId;
            TLRPC.Chat chat = chats.get(chatId);
            if (chat == null) {
                chat = MessagesController.getInstance(account).getChat(chatId);
            }
            if (chat == null) {
                return new SenderInfo("Удалённый аккаунт/канал · ID " + peerId, "", true);
            }
            String name = TextUtils.isEmpty(chat.title) ? "Канал " + chatId : chat.title;
            return new SenderInfo(name, chat.username, false);
        }

        private MediaInfo copyLocalMedia(long sourceDialogId, TLRPC.Message message) throws IOException {
            TLRPC.MessageMedia messageMedia = MessageObject.getMedia(message);
            TLRPC.Document document = MessageObject.getDocument(message);
            boolean photo = messageMedia instanceof TLRPC.TL_messageMediaPhoto;
            boolean hasMedia = photo || document != null;
            if (!hasMedia) {
                return MediaInfo.EMPTY;
            }

            String kind;
            String folder;
            if (photo) {
                kind = "photo";
                folder = "photos";
            } else if (MessageObject.isStickerMessage(message)
                    && document != null && document.mime_type != null
                    && document.mime_type.startsWith("image/")) {
                kind = "sticker";
                folder = "stickers";
            } else if (MessageObject.isVideoMessage(message)
                    || MessageObject.isRoundVideoMessage(message)
                    || MessageObject.isGifMessage(message)) {
                kind = "video";
                folder = "video_files";
            } else if (MessageObject.isVoiceMessage(message)
                    || MessageObject.isMusicMessage(message)
                    || (document != null && document.mime_type != null
                    && document.mime_type.startsWith("audio/"))) {
                kind = "audio";
                folder = "files";
            } else {
                kind = "file";
                folder = "files";
            }

            String displayName = document == null ? "Фотография" : FileLoader.getDocumentFileName(document);
            if (TextUtils.isEmpty(displayName)) {
                displayName = FileLoader.getMessageFileName(message);
            }
            if (TextUtils.isEmpty(displayName)) {
                displayName = kind;
            }
            displayName = safeFileName(displayName);

            File source = FileLoader.getInstance(account).getPathToMessage(message, false);
            if ((source == null || !source.isFile()) && !TextUtils.isEmpty(message.attachPath)) {
                File attached = new File(message.attachPath);
                if (attached.isFile()) {
                    source = attached;
                }
            }

            String metadata = "";
            if (document != null) {
                ArrayList<String> metadataParts = new ArrayList<>();
                if (!TextUtils.isEmpty(document.mime_type)) {
                    metadataParts.add(document.mime_type);
                }
                if (document.size > 0) {
                    metadataParts.add(humanSize(document.size));
                }
                metadata = TextUtils.join(" · ", metadataParts);
            }
            if (source == null || !source.isFile()) {
                return new MediaInfo(kind, "", displayName, metadata);
            }

            String extension = extensionOf(source.getName());
            if (TextUtils.isEmpty(extension)) {
                extension = extensionOf(displayName);
            }
            String destinationName = dialogToken(sourceDialogId) + "_" + message.id;
            if (!TextUtils.isEmpty(extension)) {
                destinationName += "." + extension;
            }
            File destination = new File(new File(stageDirectory, folder), destinationName);
            try {
                copyFile(source, destination);
            } catch (ExportCancelledException e) {
                throw e;
            } catch (IOException e) {
                FileLog.e("Unable to copy one media file into Agram export", e);
                if (destination.exists() && !destination.delete()) {
                    FileLog.e("Unable to remove partial Agram export media " + destination);
                }
                return new MediaInfo(kind, "", displayName, metadata);
            }
            if (TextUtils.isEmpty(metadata)) {
                metadata = humanSize(source.length());
            }
            return new MediaInfo(kind, folder + "/" + destinationName, displayName, metadata);
        }

        private void createOutput() {
            if (cancelled.get()) {
                finishCancelled();
                return;
            }
            if (!isSessionValid()) {
                finishError("Сессия или контейнер изменились во время экспорта");
                return;
            }
            if (!areSourceDialogsExportable()) {
                finishError("Защита контента была включена во время экспорта");
                return;
            }
            try {
                final int messageCount = queryCount("SELECT COUNT(*) FROM export_messages");
                final int deletedCount = queryCount("SELECT COUNT(*) FROM export_messages WHERE deleted = 1");
                notifyProgress(messageCount, messageCount,
                        LocaleController.getString(R.string.AGramExportStageHtml));
                File htmlFile = writeHtml(messageCount, deletedCount);

                if (database != null) {
                    database.close();
                    database = null;
                }
                if (databaseFile != null && databaseFile.exists() && !databaseFile.delete()) {
                    FileLog.e("Unable to delete Agram export temporary database");
                }

                if (cancelled.get()) {
                    throw new ExportCancelledException();
                }
                if (!isSessionValid()) {
                    throw new SessionChangedException();
                }
                if (!areSourceDialogsExportable()) {
                    throw new ProtectedSourceException();
                }
                File exportRoot = new File(ApplicationLoader.applicationContext.getCacheDir(),
                        "agram_chat_exports");
                File readyDirectory = new File(new File(exportRoot, safeFileName(containerId)), "ready");
                ensureDirectory(readyDirectory);
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
                String baseName = "agram-export-" + safeFileName(title) + "-" + stamp;
                if (format == Format.PDF) {
                    notifyProgress(messageCount, messageCount,
                            LocaleController.getString(R.string.AGramExportStagePdf));
                    outputFile = new File(readyDirectory, baseName + ".pdf");
                    startPdfRender(htmlFile, messageCount, deletedCount);
                    return;
                }

                notifyProgress(messageCount, messageCount,
                        LocaleController.getString(R.string.AGramExportStageZip));
                outputFile = new File(readyDirectory, baseName + ".zip");
                zipDirectory(stageDirectory, outputFile);
                completeOutput(messageCount, deletedCount);
            } catch (ExportCancelledException e) {
                finishCancelled();
            } catch (SessionChangedException e) {
                finishError("Сессия или контейнер изменились во время экспорта");
            } catch (ProtectedSourceException e) {
                finishError("Защита контента была включена во время экспорта");
            } catch (Throwable e) {
                FileLog.e("Unable to create Agram chat export", e);
                finishError("Не удалось создать файл экспорта");
            }
        }

        private void startPdfRender(File htmlFile, int messageCount, int deletedCount) {
            AgramHtmlPdfRenderer.RenderTask task = AgramHtmlPdfRenderer.render(
                    htmlFile, outputFile, new AgramHtmlPdfRenderer.Callback() {
                        @Override
                        public void onSuccess() {
                            exportQueue.postRunnable(() -> {
                                pdfRenderTask = null;
                                if (!completed.get()) {
                                    completeOutput(messageCount, deletedCount);
                                }
                            });
                        }

                        @Override
                        public void onError(String message) {
                            exportQueue.postRunnable(() -> {
                                pdfRenderTask = null;
                                if (completed.get()) {
                                    return;
                                }
                                if (cancelled.get()) {
                                    finishCancelled();
                                } else {
                                    finishError(TextUtils.isEmpty(message)
                                            ? "Не удалось сформировать PDF"
                                            : message);
                                }
                            });
                        }
                    });
            if (task == null) {
                finishError("Не удалось запустить формирование PDF");
                return;
            }
            pdfRenderTask = task;
            if (cancelled.get() || completed.get()) {
                task.cancel();
            }
        }

        private void completeOutput(int messageCount, int deletedCount) {
            if (completed.get()) {
                return;
            }
            if (cancelled.get()) {
                finishCancelled();
                return;
            }
            if (outputFile == null || !outputFile.isFile() || outputFile.length() == 0) {
                finishError("Не удалось создать файл экспорта");
                return;
            }
            if (!isSessionValid()) {
                finishError("Сессия или контейнер изменились во время экспорта");
                return;
            }
            if (!areSourceDialogsExportable()) {
                finishError("Защита контента была включена во время экспорта");
                return;
            }
            deleteRecursively(stageDirectory);
            stageDirectory = null;
            finishSuccess(outputFile, messageCount, deletedCount);
        }

        private int queryCount(String sql) {
            try (Cursor cursor = database.rawQuery(sql, null)) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        }

        private File writeHtml(int messageCount, int deletedCount) throws IOException {
            File html = new File(stageDirectory, "messages.html");
            SimpleDateFormat dayFormat = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
            SimpleDateFormat dayKeyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            SimpleDateFormat fullFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss z", Locale.getDefault());
            TimeZone zone = TimeZone.getDefault();
            dayFormat.setTimeZone(zone);
            dayKeyFormat.setTimeZone(zone);
            timeFormat.setTimeZone(zone);
            fullFormat.setTimeZone(zone);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(html), StandardCharsets.UTF_8), COPY_BUFFER_SIZE)) {
                writer.write("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n");
                writer.write("<meta charset=\"utf-8\">\n");
                writer.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
                writer.write("<title>" + escapeHtml(title) + "</title>\n");
                writer.write("<link href=\"css/style.css\" rel=\"stylesheet\">\n");
                writer.write("<script src=\"js/script.js\" type=\"text/javascript\"></script>\n");
                writer.write("</head>\n<body>\n<div class=\"page_wrap\">\n");
                writer.write("<div class=\"page_header\"><div class=\"content\">\n");
                writer.write("<div class=\"title\">" + escapeHtml(title) + "</div>\n");
                writer.write("<div class=\"subtitle\">" + escapeHtml(buildSubtitle(messageCount, deletedCount))
                        + "</div>\n</div></div>\n");
                writer.write("<div class=\"export_notice\">Удалённые аккаунты сохраняются в выгрузке "
                        + "с их локально доступным именем и ID. Сообщения, удалённые с сервера и "
                        + "сохранённые Agram, отмечены {DELETED}. Секретные, исчезающие и защищённые "
                        + "сообщения в экспорт не включаются.</div>\n");
                writer.write("<div class=\"page_body\">\n");

                if (messageCount == 0) {
                    writer.write("<div class=\"empty_export\">В выбранном диапазоне сообщений нет.</div>\n");
                } else {
                    String previousDay = "";
                    try (Cursor cursor = database.rawQuery("SELECT source_dialog_id,mid,date,sender_name,username,"
                            + "sender_deleted,body,reply_mid,media_kind,media_rel,media_name,"
                            + "media_meta,reactions_html,deleted FROM export_messages "
                            + "ORDER BY date ASC, source_dialog_id ASC, mid ASC", null)) {
                        int rendered = 0;
                        while (cursor.moveToNext()) {
                            if (cancelled.get()) {
                                throw new ExportCancelledException();
                            }
                            long sourceDialogId = cursor.getLong(0);
                            int mid = cursor.getInt(1);
                            int timestamp = cursor.getInt(2);
                            Date date = new Date(timestamp * 1000L);
                            String dayKey = dayKeyFormat.format(date);
                            if (!dayKey.equals(previousDay)) {
                                writer.write("<div class=\"day_header\"><span>"
                                        + dayFormat.format(date) + "</span></div>\n");
                                previousDay = dayKey;
                            }
                            writeMessage(writer, cursor, sourceDialogId, mid, date,
                                    timeFormat, fullFormat);
                            rendered++;
                            if ((rendered & 127) == 0) {
                                notifyProgress(rendered, messageCount,
                                        LocaleController.getString(R.string.AGramExportStageHtml));
                            }
                        }
                    }
                }
                writer.write("</div>\n</div>\n</body>\n</html>\n");
            }
            return html;
        }

        private String buildSubtitle(int messageCount, int deletedCount) {
            ArrayList<String> parts = new ArrayList<>();
            parts.add("Сообщений: " + messageCount);
            parts.add("удалённых: " + deletedCount);
            if (senderId != 0) {
                parts.add("участник: " + resolveSender(senderId).name);
            }
            if (minDateSec != 0 || maxDateSec != 0) {
                SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
                String from = minDateSec == 0 ? "начала" : format.format(new Date(minDateSec * 1000L));
                String to = maxDateSec == 0 ? "сегодня" : format.format(new Date(maxDateSec * 1000L));
                parts.add("период: " + from + " — " + to);
            }
            return TextUtils.join(" · ", parts);
        }

        private void writeMessage(BufferedWriter writer, Cursor cursor, long sourceDialogId,
                                  int mid, Date date,
                                  SimpleDateFormat timeFormat, SimpleDateFormat fullFormat)
                throws IOException {
            String senderName = value(cursor, 3);
            String username = value(cursor, 4);
            boolean senderDeleted = cursor.getInt(5) != 0;
            String body = value(cursor, 6);
            int replyMid = cursor.getInt(7);
            String mediaKind = value(cursor, 8);
            String mediaRel = value(cursor, 9);
            String mediaName = value(cursor, 10);
            String mediaMeta = value(cursor, 11);
            String reactions = value(cursor, 12);
            boolean deleted = cursor.getInt(13) != 0;
            String anchor = messageAnchor(sourceDialogId, mid);

            writer.write("<div class=\"message" + (deleted ? " deleted" : "")
                    + "\" id=\"" + anchor + "\">\n");
            writer.write("<div class=\"avatar\">" + escapeHtml(initials(senderName)) + "</div>\n");
            writer.write("<div class=\"message_body\">\n<div class=\"message_head\">\n");
            writer.write("<div class=\"sender" + (senderDeleted ? " deleted_account" : "") + "\">"
                    + escapeHtml(senderName));
            if (!TextUtils.isEmpty(username)) {
                writer.write("<span class=\"username\">@" + escapeHtml(username) + "</span>");
            }
            writer.write("</div>\n<div class=\"date\" title=\"" + escapeHtml(fullFormat.format(date))
                    + "\">");
            if (deleted) {
                writer.write("<span class=\"deleted_tag\">{DELETED}</span>");
            }
            writer.write(timeFormat.format(date) + "</div>\n</div>\n");

            if (replyMid != 0) {
                String replyAnchor = messageAnchor(sourceDialogId, replyMid);
                writer.write("<div class=\"reply\"><a href=\"#" + replyAnchor
                        + "\" onclick=\"return goToMessage('" + replyAnchor + "')\">"
                        + "<div class=\"reply_sender\">Ответ</div>"
                        + "<div class=\"reply_text\">Сообщение ID " + replyMid
                        + "</div></a></div>\n");
            }
            writeMedia(writer, mediaKind, mediaRel, mediaName, mediaMeta);
            if (!TextUtils.isEmpty(body)) {
                writer.write("<div class=\"text\">" + escapeHtml(body) + "</div>\n");
            }
            if (!TextUtils.isEmpty(reactions)) {
                writer.write(reactions);
                writer.write('\n');
            }
            writer.write("<div class=\"actions\"><span class=\"msg_id\">ID " + mid + "</span>");
            String link = telegramMessageLink(sourceDialogId, mid);
            if (!TextUtils.isEmpty(link)) {
                writer.write("<a href=\"" + escapeHtml(link)
                        + "\" target=\"_blank\">Открыть в Telegram ↗</a>");
            }
            writer.write("</div>\n</div>\n</div>\n");
        }

        private void writeMedia(BufferedWriter writer, String kind, String relativePath,
                                String name, String metadata) throws IOException {
            if (TextUtils.isEmpty(kind)) {
                return;
            }
            if (TextUtils.isEmpty(relativePath)) {
                writer.write("<div class=\"media\"><span class=\"media_missing\">"
                        + escapeHtml(TextUtils.isEmpty(name) ? "Медиа не загружено на устройство" : name)
                        + " · не загружено на устройство</span></div>\n");
                return;
            }
            String path = escapeHtml(relativePath);
            writer.write("<div class=\"media\">");
            if ("photo".equals(kind)) {
                writer.write("<a href=\"" + path + "\" target=\"_blank\"><img class=\"photo\" src=\""
                        + path + "\" loading=\"lazy\" alt=\"photo\"></a>");
            } else if ("sticker".equals(kind)) {
                writer.write("<a href=\"" + path + "\" target=\"_blank\"><img class=\"sticker\" src=\""
                        + path + "\" loading=\"lazy\" alt=\"sticker\"></a>");
            } else if ("video".equals(kind)) {
                writer.write("<video src=\"" + path + "\" controls preload=\"metadata\"></video>"
                        + "<div class=\"video_pdf_note\">🎬 Видео доступно в HTML-версии экспорта</div>");
            } else if ("audio".equals(kind)) {
                writer.write("<audio src=\"" + path + "\" controls preload=\"metadata\"></audio>"
                        + "<div class=\"audio_pdf_note\">🔊 Аудио доступно в HTML-версии экспорта</div>");
            } else {
                writer.write("<a class=\"file_card\" href=\"" + path + "\" target=\"_blank\">"
                        + "<div class=\"file_icon\">📎</div><div class=\"file_info\">"
                        + "<div class=\"file_name\">" + escapeHtml(name) + "</div>"
                        + "<div class=\"file_meta\">" + escapeHtml(metadata) + "</div>"
                        + "</div></a>");
            }
            writer.write("</div>\n");
        }

        private String renderReactions(TLRPC.Message message) {
            if (message.reactions == null || message.reactions.results == null
                    || message.reactions.results.isEmpty()) {
                return "";
            }
            StringBuilder result = new StringBuilder("<div class=\"reactions\">");
            for (int i = 0; i < message.reactions.results.size(); i++) {
                TLRPC.ReactionCount item = message.reactions.results.get(i);
                if (item == null) {
                    continue;
                }
                String label;
                if (item.reaction instanceof TLRPC.TL_reactionEmoji) {
                    label = ((TLRPC.TL_reactionEmoji) item.reaction).emoticon;
                } else if (item.reaction instanceof TLRPC.TL_reactionPaid) {
                    label = "⭐";
                } else if (item.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                    label = "Custom emoji";
                } else {
                    label = "Reaction";
                }
                result.append("<span class=\"reaction\"><span>")
                        .append(escapeHtml(label)).append("</span><span>")
                        .append(item.count).append("</span></span>");
            }
            return result.append("</div>").toString();
        }

        private String telegramMessageLink(long sourceDialogId, int messageId) {
            if (sourceDialogId >= 0) {
                return "";
            }
            TLRPC.Chat chat = chats.get(-sourceDialogId);
            if (chat == null) {
                chat = MessagesController.getInstance(account).getChat(-sourceDialogId);
            }
            if (chat == null) {
                return "";
            }
            if (!TextUtils.isEmpty(chat.username)) {
                return "https://t.me/" + chat.username + "/" + messageId;
            }
            if (ChatObject.isChannel(chat)) {
                return "https://t.me/c/" + chat.id + "/" + messageId;
            }
            return "";
        }

        private String dialogToken(long sourceDialogId) {
            return sourceDialogId < 0 ? "c" + (-sourceDialogId) : "u" + sourceDialogId;
        }

        private String messageAnchor(long sourceDialogId, int messageId) {
            return "message-" + dialogToken(sourceDialogId) + "-" + messageId;
        }

        private void cancel() {
            if (completed.get() || !cancelled.compareAndSet(false, true)) {
                return;
            }
            int requestId = currentRequestId;
            if (requestId != 0) {
                ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
            }
            AgramHtmlPdfRenderer.RenderTask renderTask = pdfRenderTask;
            if (renderTask != null) {
                renderTask.cancel();
            }
            exportQueue.postRunnable(this::finishCancelled);
        }

        private boolean areSourceDialogsExportable() {
            MessagesController controller = MessagesController.getInstance(account);
            for (long sourceDialogId : sourceDialogIds) {
                if (sourceDialogId == 0 || DialogObject.isEncryptedDialog(sourceDialogId)
                        || (!DialogObject.isUserDialog(sourceDialogId)
                        && !DialogObject.isChatDialog(sourceDialogId))) {
                    return false;
                }
                // Treat missing peer metadata as protected. Without the authoritative local peer
                // object we cannot prove that noforwards is disabled, especially at the final
                // post-output recheck where exporting must fail closed.
                if (DialogObject.isUserDialog(sourceDialogId)) {
                    if (controller.getUser(sourceDialogId) == null
                            || controller.getUserFull(sourceDialogId) == null
                            || controller.isPeerNoForwards(sourceDialogId)) {
                        return false;
                    }
                } else {
                    TLRPC.Chat sourceChat = controller.getChat(-sourceDialogId);
                    if (sourceChat == null || sourceChat.noforwards
                            || controller.isPeerNoForwards(sourceDialogId)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean isSessionValid() {
            UserConfig config = UserConfig.getInstance(account);
            if (!config.isClientActivated() || clientUserId == 0
                    || config.getClientUserId() != clientUserId
                    || RETIRED_CONTAINERS.contains(containerId)) {
                return false;
            }
            AgramContainerManager.ContainerRecord current =
                    AgramContainerManager.getInstance().getContainer(account);
            return current != null && current.isStorageAccessible() && !TextUtils.isEmpty(containerId)
                    && TextUtils.equals(containerId, current.id);
        }

        private void notifyProgress(int processed, int total, String stage) {
            if (listener == null || completed.get() || cancelled.get()) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!completed.get() && !cancelled.get()) {
                    listener.onProgress(processed, total, stage);
                }
            });
        }

        private void finishSuccess(File archive, int messageCount, int deletedCount) {
            if (!isSessionValid()) {
                finishError("Сессия или контейнер изменились во время экспорта");
                return;
            }
            if (!areSourceDialogsExportable()) {
                finishError("Защита контента была включена во время экспорта");
                return;
            }
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            closeAndCleanup(false);
            if (listener != null) {
                AndroidUtilities.runOnUIThread(() -> listener.onDone(archive, messageCount, deletedCount));
            }
            exportQueue.recycle();
        }

        private void finishError(String message) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            closeAndCleanup(true);
            if (listener != null) {
                AndroidUtilities.runOnUIThread(() -> listener.onError(message));
            }
            exportQueue.recycle();
        }

        private void finishCancelled() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            closeAndCleanup(true);
            exportQueue.recycle();
        }

        private void closeAndCleanup(boolean deleteOutput) {
            AgramHtmlPdfRenderer.RenderTask renderTask = pdfRenderTask;
            pdfRenderTask = null;
            if (renderTask != null) {
                renderTask.cancel();
            }
            try {
                if (database != null) {
                    database.close();
                    database = null;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (stageDirectory != null) {
                deleteRecursively(stageDirectory);
                stageDirectory = null;
            }
            if (deleteOutput && outputFile != null && outputFile.exists() && !outputFile.delete()) {
                FileLog.e("Unable to remove incomplete Agram export");
            }
        }

        private void copyAsset(String asset, File destination) throws IOException {
            try (InputStream input = new BufferedInputStream(
                    ApplicationLoader.applicationContext.getAssets().open(asset));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                copyStream(input, output);
            }
        }

        private void copyFile(File source, File destination) throws IOException {
            try (InputStream input = new BufferedInputStream(new FileInputStream(source));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                copyStream(input, output);
            }
        }

        private void copyStream(InputStream input, OutputStream output) throws IOException {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (cancelled.get()) {
                    throw new ExportCancelledException();
                }
                output.write(buffer, 0, read);
            }
        }

        private void zipDirectory(File directory, File zipFile) throws IOException {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                    new FileOutputStream(zipFile), COPY_BUFFER_SIZE))) {
                zipChildren(directory, directory, zip);
            }
        }

        private void zipChildren(File root, File current, ZipOutputStream zip) throws IOException {
            File[] files = current.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                if (cancelled.get()) {
                    throw new ExportCancelledException();
                }
                if (file.equals(databaseFile)) {
                    continue;
                }
                if (file.isDirectory()) {
                    zipChildren(root, file, zip);
                    continue;
                }
                String name = root.toURI().relativize(file.toURI()).getPath();
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(file.lastModified());
                zip.putNextEntry(entry);
                try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                    copyStream(input, zip);
                }
                zip.closeEntry();
            }
        }
    }

    private static final class SenderInfo {
        final String name;
        final String username;
        final boolean deleted;

        SenderInfo(String name, String username, boolean deleted) {
            this.name = name;
            this.username = username == null ? "" : username;
            this.deleted = deleted;
        }
    }

    private static final class MediaInfo {
        static final MediaInfo EMPTY = new MediaInfo("", "", "", "");

        final String kind;
        final String relativePath;
        final String displayName;
        final String metadata;

        MediaInfo(String kind, String relativePath, String displayName, String metadata) {
            this.kind = kind == null ? "" : kind;
            this.relativePath = relativePath == null ? "" : relativePath;
            this.displayName = displayName == null ? "" : displayName;
            this.metadata = metadata == null ? "" : metadata;
        }
    }

    private static final class ExportCancelledException extends IOException {
    }

    private static final class SessionChangedException extends IOException {
    }

    private static final class ProtectedSourceException extends IOException {
    }

    private static String value(Cursor cursor, int column) {
        return cursor.isNull(column) ? "" : cursor.getString(column);
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create " + directory);
        }
    }

    private static String initials(String name) {
        if (TextUtils.isEmpty(name)) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 0 || TextUtils.isEmpty(parts[0])) {
            return "?";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String safeFileName(String value) {
        String safe = TextUtils.isEmpty(value) ? "chat" : value.trim();
        safe = safe.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "_")
                .replaceAll("\\s+", " ");
        while (safe.endsWith(".") || safe.endsWith(" ")) {
            safe = safe.substring(0, safe.length() - 1);
        }
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        return TextUtils.isEmpty(safe) ? "chat" : safe;
    }

    private static String extensionOf(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String extension = name.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "");
        return extension.length() > 10 ? "" : extension.toLowerCase(Locale.US);
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        double size = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return unit == 0 ? ((long) size) + " B"
                : String.format(Locale.US, "%.1f %s", size, units[unit]);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            FileLog.e("Unable to delete Agram export temporary path " + file);
        }
    }
}
