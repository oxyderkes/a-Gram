package org.telegram.messenger;

import static org.telegram.messenger.CacheByChatsController.KEEP_MEDIA_TYPE_STORIES;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AutoDeleteMediaTask {

    public static Set<String> usingFilePaths = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void run() {
        int time = (int) (System.currentTimeMillis() / 1000);
        if (Math.abs(time - SharedConfig.lastKeepMediaCheckTime) < 24 * 60 * 60) {
            return;
        }
        SharedConfig.lastKeepMediaCheckTime = time;
        Utilities.cacheClearQueue.postRunnable(() -> {
            long startTime = System.currentTimeMillis();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("checkKeepMedia start task");
            }
            boolean hasExceptions = false;
            ArrayList<CacheByChatsController> cacheByChatsControllers = new ArrayList<>();
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    CacheByChatsController cacheByChatsController = UserConfig.getInstance(account).getMessagesController().getCacheByChatsController();
                    cacheByChatsControllers.add(cacheByChatsController);
                    if (cacheByChatsController.getKeepMediaExceptionsByDialogs().size() > 0) {
                        hasExceptions = true;
                    }
                }
            }

            int[] keepMediaByTypes = new int[4];
            boolean allKeepMediaTypesForever = true;
            long keepMediaMinSeconds = Long.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                keepMediaByTypes[i] = SharedConfig.getPreferences().getInt("keep_media_type_" + i, CacheByChatsController.getDefault(i));
                if (keepMediaByTypes[i] != CacheByChatsController.KEEP_MEDIA_FOREVER) {
                    allKeepMediaTypesForever = false;
                }
                long days = CacheByChatsController.getDaysInSeconds(keepMediaByTypes[i]);
                if (days < keepMediaMinSeconds) {
                    keepMediaMinSeconds = days;
                }
            }
            if (hasExceptions) {
                allKeepMediaTypesForever = false;
            }
            int autoDeletedFiles = 0;
            long autoDeletedFilesSize = 0;

            int deletedFilesBySize = 0;
            long deletedFilesBySizeSize = 0;
            int skippedFiles = 0;

            //if (!allKeepMediaTypesForever) {
                //long currentTime = time - 60 * 60 * 24 * days;
                final ArrayList<MediaDirectory> paths = getManagedMediaDirectories();
                final ArrayList<MediaDirectory> recursivePaths = getTopLevelMediaDirectories(paths);
                for (int a = 0; a < paths.size(); a++) {
                    MediaDirectory mediaDirectory = paths.get(a);
                    if (allKeepMediaTypesForever && (mediaDirectory.type == FileLoader.MEDIA_DIR_AUDIO || mediaDirectory.type == FileLoader.MEDIA_DIR_DOCUMENT)) {
                        continue;
                    }
                    boolean isCacheDir = mediaDirectory.type == FileLoader.MEDIA_DIR_CACHE;
                    File dir = mediaDirectory.file;
                    try {
                        File[] files = dir.listFiles();
                        ArrayList<CacheByChatsController.KeepMediaFile> keepMediaFiles = new ArrayList<>();
                        if (files != null) {
                            for (int i = 0; i < files.length; i++) {
                                if (files[i].isDirectory() || usingFilePaths.contains(files[i].getAbsolutePath())) {
                                    continue;
                                }
                                keepMediaFiles.add(new CacheByChatsController.KeepMediaFile(files[i]));
                            }
                        }
                        for (int i = 0; i < cacheByChatsControllers.size(); i++) {
                            cacheByChatsControllers.get(i).lookupFiles(keepMediaFiles);
                        }
                        for (int i = 0; i < keepMediaFiles.size(); i++) {
                            CacheByChatsController.KeepMediaFile file = keepMediaFiles.get(i);
                            long timeLocal;
                            if (file.isStory) {
                                long seconds = CacheByChatsController.getDaysInSeconds(keepMediaByTypes[KEEP_MEDIA_TYPE_STORIES]);
                                timeLocal = time - seconds;
                            } else {
                                if (file.keepMedia == CacheByChatsController.KEEP_MEDIA_FOREVER) {
                                    continue;
                                }
                                long seconds;
                                if (file.keepMedia >= 0) {
                                    seconds = CacheByChatsController.getDaysInSeconds(file.keepMedia);
                                } else if (file.dialogType >= 0) {
                                    seconds = CacheByChatsController.getDaysInSeconds(keepMediaByTypes[file.dialogType]);
                                } else if (isCacheDir) {
                                    continue;
                                } else {
                                    seconds = keepMediaMinSeconds;
                                }
                                if (seconds == Long.MAX_VALUE) {
                                    continue;
                                }

                                timeLocal = time - seconds;
                            }
                            long lastUsageTime = Utilities.getLastUsageFileTime(file.file.getAbsolutePath());
                            boolean needDelete = lastUsageTime > 316000000 && lastUsageTime < timeLocal && !usingFilePaths.contains(file.file.getPath());
                            if (needDelete) {
                                long deletedFileSize = file.file.length();
                                if (AgramDeletedMediaStore.deleteOrDefer(file.file)) {
                                    continue;
                                }
                                if (BuildVars.LOGS_ENABLED) {
                                    autoDeletedFiles++;
                                    autoDeletedFilesSize += deletedFileSize;
                                }
                                if (BuildVars.DEBUG_PRIVATE_VERSION) {
                                    FileLog.d("delete file " + file.file.getPath() + " last_usage_time=" + lastUsageTime + " time_local=" + timeLocal + " story=" + file.isStory);
                                }
                            }
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            //}

            int maxCacheGb = SharedConfig.getPreferences().getInt("cache_limit", Integer.MAX_VALUE);
            if (maxCacheGb != Integer.MAX_VALUE) {
                long maxCacheSize;
                if (maxCacheGb == 1) {
                    maxCacheSize = 1024L * 1024L * 300L;
                } else {
                    maxCacheSize = maxCacheGb * 1024L * 1024L * 1000L;
                }
                long totalSize = 0;
                for (int a = 0; a < recursivePaths.size(); a++) {
                    totalSize += Utilities.getDirSize(recursivePaths.get(a).file.getAbsolutePath(), 0, true);
                }
                if (totalSize > maxCacheSize) {
                    ArrayList<FileInfoInternal> allFiles = new ArrayList<>();
                    for (int a = 0; a < recursivePaths.size(); a++) {
                        File dir = recursivePaths.get(a).file;
                        fillFilesRecursive(dir, allFiles);
                    }
                    for (int i = 0; i < cacheByChatsControllers.size(); i++) {
                        cacheByChatsControllers.get(i).lookupFiles(allFiles);
                    }
                    Collections.sort(allFiles, (o1, o2) -> {
                        if (o2.lastUsageDate > o1.lastUsageDate) {
                            return -1;
                        } else if (o2.lastUsageDate < o1.lastUsageDate) {
                            return 1;
                        }
                        return 0;
                    });

                    for (int i = 0; i < allFiles.size(); i++) {
                        if (allFiles.get(i).keepMedia == CacheByChatsController.KEEP_MEDIA_FOREVER) {
                            continue;
                        }
                        if (allFiles.get(i).lastUsageDate <= 0) {
                            skippedFiles++;
                            continue;
                        }
                        long size = allFiles.get(i).file.length();
                        if (AgramDeletedMediaStore.deleteOrDefer(allFiles.get(i).file)) {
                            continue;
                        }
                        totalSize -= size;
                        deletedFilesBySize++;
                        deletedFilesBySizeSize += size;

                        if (totalSize < maxCacheSize) {
                            break;
                        }
                    }
                }
            }

            long currentTime = time - 60 * 60 * 24;
            for (File cacheDir : FileLoader.getAllDirectories(FileLoader.MEDIA_DIR_CACHE)) {
                File stickersPath = new File(cacheDir, "acache");
                if (stickersPath.exists()) {
                    try {
                        Utilities.clearDir(stickersPath.getAbsolutePath(), 0, currentTime, false);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            }
            MessagesController.getGlobalMainSettings().edit()
                    .putInt("lastKeepMediaCheckTime", SharedConfig.lastKeepMediaCheckTime)
                    .apply();

            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("checkKeepMedia task end time " + (System.currentTimeMillis() - startTime) + " auto deleted info: files " + autoDeletedFiles + " size " + AndroidUtilities.formatFileSize(autoDeletedFilesSize) + "   deleted by size limit info: files " + deletedFilesBySize + " size " + AndroidUtilities.formatFileSize(deletedFilesBySizeSize) + " unknownTimeFiles " + skippedFiles);
            }
        });
    }

    /**
     * Cache maintenance is application-wide. Enumerate all UUID-scoped roots explicitly instead
     * of inheriting the account that happens to be selected when this background task runs.
     * Cache is visited first so a fallback root shared by several media types keeps cache
     * semantics and is never scanned or charged more than once.
     */
    private static ArrayList<MediaDirectory> getManagedMediaDirectories() {
        final int[] types = {
                FileLoader.MEDIA_DIR_CACHE,
                FileLoader.MEDIA_DIR_IMAGE,
                FileLoader.MEDIA_DIR_AUDIO,
                FileLoader.MEDIA_DIR_VIDEO,
                FileLoader.MEDIA_DIR_DOCUMENT,
                FileLoader.MEDIA_DIR_FILES,
                FileLoader.MEDIA_DIR_STORIES,
                FileLoader.MEDIA_DIR_IMAGE_PUBLIC,
                FileLoader.MEDIA_DIR_VIDEO_PUBLIC
        };
        ArrayList<MediaDirectory> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int type : types) {
            for (File directory : FileLoader.getAllDirectories(type)) {
                if (directory == null) {
                    continue;
                }
                String key;
                try {
                    key = directory.getCanonicalPath();
                } catch (IOException e) {
                    key = directory.getAbsolutePath();
                }
                if (seen.add(key)) {
                    result.add(new MediaDirectory(type, directory));
                }
            }
        }
        return result;
    }

    private static ArrayList<MediaDirectory> getTopLevelMediaDirectories(ArrayList<MediaDirectory> directories) {
        ArrayList<MediaDirectory> result = new ArrayList<>();
        ArrayList<String> roots = new ArrayList<>();
        for (MediaDirectory directory : directories) {
            String path;
            try {
                path = directory.file.getCanonicalPath();
            } catch (IOException e) {
                path = directory.file.getAbsolutePath();
            }
            boolean covered = false;
            for (String root : roots) {
                if (path.equals(root) || path.startsWith(root + File.separator)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                roots.add(path);
                result.add(directory);
            }
        }
        return result;
    }

    private static class MediaDirectory {
        final int type;
        final File file;

        MediaDirectory(int type, File file) {
            this.type = type;
            this.file = file;
        }
    }

    private static void fillFilesRecursive(final File fromFolder, ArrayList<FileInfoInternal> fileInfoList) {
        if (fromFolder == null) {
            return;
        }
        File[] files = fromFolder.listFiles();
        if (files == null) {
            return;
        }
        for (final File fileEntry : files) {
            if (fileEntry.isDirectory()) {
                fillFilesRecursive(fileEntry, fileInfoList);
            } else {
                if (fileEntry.getName().equals(".nomedia")) {
                    continue;
                }
                if (usingFilePaths.contains(fileEntry.getAbsolutePath())) {
                    continue;
                }
                fileInfoList.add(new FileInfoInternal(fileEntry));
            }
        }
    }

    private static class FileInfoInternal extends CacheByChatsController.KeepMediaFile {
        final long lastUsageDate;

        private FileInfoInternal(File file) {
            super(file);
            this.lastUsageDate = Utilities.getLastUsageFileTime(file.getAbsolutePath());
        }
    }

    public static void lockFile(File file) {
        if (file == null) {
            return;
        }
        lockFile(file.getAbsolutePath());
    }

    public static void unlockFile(File file) {
        if (file == null) {
            return;
        }
        unlockFile(file.getAbsolutePath());
    }

    public static void lockFile(String file) {
        if (file == null) {
            return;
        }
        usingFilePaths.add(file);
    }

    public static void unlockFile(String file) {
        if (file == null) {
            return;
        }
        usingFilePaths.remove(file);
    }

}
