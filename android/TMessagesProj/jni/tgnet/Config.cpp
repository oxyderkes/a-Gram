/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#include <sys/stat.h>
#include <unistd.h>
#include <errno.h>
#include <cstring>
#include <fcntl.h>
#include "Config.h"
#include "ConnectionsManager.h"
#include "FileLog.h"
#include "BuffersStorage.h"

namespace {

bool validPayloadSize(uint32_t size, long fileSize) {
    return size > 0 && size <= 16 * 1024 * 1024 && fileSize > 0
            && static_cast<uint64_t>(size) + sizeof(uint32_t) == static_cast<uint64_t>(fileSize);
}

int fileState(const std::string &path) {
    struct stat metadata;
    if (stat(path.c_str(), &metadata) != 0) {
        return errno == ENOENT ? 0 : -1;
    }
    return S_ISREG(metadata.st_mode) ? 1 : -1;
}

bool isValidConfigFile(const std::string &path, bool &readError) {
    FILE *file = fopen(path.c_str(), "rb");
    if (file == nullptr) {
        if (errno != ENOENT) readError = true;
        return false;
    }

    bool valid = false;
    if (fseek(file, 0, SEEK_END) == 0) {
        long fileSize = ftell(file);
        if (fileSize < 0) {
            readError = true;
        } else if (fseek(file, 0, SEEK_SET) != 0) {
            readError = true;
        } else if (fileSize > static_cast<long>(sizeof(uint32_t))) {
            uint32_t size = 0;
            valid = fread(&size, sizeof(uint32_t), 1, file) == 1
                    && validPayloadSize(size, fileSize);
        }
    } else {
        readError = true;
    }
    const bool ioError = ferror(file) != 0;
    const bool closeError = fclose(file) != 0;
    if (ioError || closeError) readError = true;
    return valid;
}

bool syncParent(const std::string &path) {
    const auto slash = path.find_last_of('/');
    const std::string directory = slash == std::string::npos ? "." : path.substr(0, slash);
    const int descriptor = open(directory.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (descriptor < 0) {
        return false;
    }
    const bool synced = fsync(descriptor) == 0;
    const bool closed = close(descriptor) == 0;
    return synced && closed;
}

}

Config::Config(int32_t instance, std::string fileName) {
    instanceNum = instance;
    configPath = ConnectionsManager::getInstance(instanceNum).currentConfigPath + fileName;
    backupPath = configPath + ".bak";
    const int backupState = fileState(backupPath);
    if (backupState < 0 || fileState(configPath) < 0) {
        readFailure = true;
        return;
    }
    if (backupState > 0) {
        if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) backup file found %s", this, configPath.c_str(), backupPath.c_str());
        bool configValid = isValidConfigFile(configPath, readFailure);
        bool backupValid = isValidConfigFile(backupPath, readFailure);
        if (readFailure) return;
        if (!configValid && !backupValid) {
            // Preserve both files for recovery; an unreadable backup is not a
            // license to initialize and overwrite a potentially active session.
            readFailure = true;
            return;
        }
        if (!configValid && backupValid) {
            if (remove(configPath.c_str()) != 0 && errno != ENOENT) {
                readFailure = true;
                if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) unable to remove invalid config, %s", this, configPath.c_str(), strerror(errno));
            } else if (rename(backupPath.c_str(), configPath.c_str()) != 0) {
                readFailure = true;
                if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) unable to restore backup %s, %s", this, configPath.c_str(), backupPath.c_str(), strerror(errno));
            } else {
                readFailure = !syncParent(configPath);
            }
        } else {
            // A complete main config always wins. An invalid backup cannot be
            // used for recovery and only risks rolling a later session back.
            if (remove(backupPath.c_str()) != 0 && errno != ENOENT) {
                readFailure = true;
                if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) unable to remove stale backup %s, %s", this, configPath.c_str(), backupPath.c_str(), strerror(errno));
            }
            if (!syncParent(configPath)) {
                readFailure = true;
            }
        }
    }
}

NativeByteBuffer *Config::readConfig() {
    if (readFailure) {
        return nullptr;
    }
    NativeByteBuffer *buffer = nullptr;
    FILE *file = fopen(configPath.c_str(), "rb");
    if (file != nullptr) {
        const bool endSeeked = fseek(file, 0, SEEK_END) == 0;
        const long fileSize = endSeeked ? ftell(file) : -1;
        const bool rewound = fileSize >= 0 && fseek(file, 0, SEEK_SET) == 0;
        uint32_t size = 0;
        const bool sizeRead = rewound && fread(&size, sizeof(uint32_t), 1, file) == 1;
        if (sizeRead && validPayloadSize(size, fileSize)) {
            buffer = BuffersStorage::getInstance().getFreeBuffer(size);
            if (fread(buffer->bytes(), sizeof(uint8_t), size, file) != size) {
                buffer->reuse();
                buffer = nullptr;
            }
        }
        const bool ioError = ferror(file) != 0;
        const bool closeError = fclose(file) != 0;
        if (ioError || closeError || buffer == nullptr) {
            if (buffer != nullptr) {
                buffer->reuse();
                buffer = nullptr;
            }
            readFailure = true;
        }
    } else if (errno != ENOENT) {
        readFailure = true;
    }
    return buffer;
}

bool Config::writeConfig(NativeByteBuffer *buffer) {
    if (readFailure) {
        return false;
    }
    // The owner UUID and all DC key material are one payload. Never expose a
    // partially written main file, or acknowledge a rename before directory fsync.
    const std::string temporaryPath = configPath + ".tmp";
    FILE *file = fopen(temporaryPath.c_str(), "wb");
    if (file == nullptr) {
        return false;
    }
    const uint32_t size = buffer->position();
    bool ok = fchmod(fileno(file), 0600) == 0;
    ok = fwrite(&size, sizeof(size), 1, file) == 1 && ok;
    ok = fwrite(buffer->bytes(), 1, size, file) == size && ok;
    ok = fflush(file) == 0 && ok;
    ok = fsync(fileno(file)) == 0 && ok;
    ok = fclose(file) == 0 && ok;
    if (!ok) {
        remove(temporaryPath.c_str());
        return false;
    }
    const int mainState = fileState(configPath);
    if (mainState < 0 || fileState(backupPath) < 0) {
        remove(temporaryPath.c_str());
        return false;
    }
    const bool hadMain = mainState > 0;
    if ((remove(backupPath.c_str()) != 0 && errno != ENOENT)
            || (hadMain && rename(configPath.c_str(), backupPath.c_str()) != 0)) {
        remove(temporaryPath.c_str());
        return false;
    }
    if (!syncParent(configPath) || rename(temporaryPath.c_str(), configPath.c_str()) != 0) {
        if (hadMain && (rename(backupPath.c_str(), configPath.c_str()) != 0 || !syncParent(configPath))) {
            readFailure = true;
        }
        remove(temporaryPath.c_str());
        return false;
    }
    if (!syncParent(configPath)) {
        // Restore the last durable payload when possible. Regardless of rollback
        // success the caller must keep the transport paused and report failure.
        if (hadMain) {
            if (rename(backupPath.c_str(), configPath.c_str()) != 0 || !syncParent(configPath)) {
                readFailure = true;
            }
        } else if (remove(configPath.c_str()) != 0 || !syncParent(configPath)) {
            readFailure = true;
        }
        return false;
    }
    if (remove(backupPath.c_str()) != 0 && errno != ENOENT) {
        return false;
    }
    return syncParent(configPath);
}
