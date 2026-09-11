from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android"
errors: list[str] = []


def read(relative: str) -> str:
    path = ANDROID / relative
    if not path.is_file():
        errors.append(f"missing required overlay file: android/{relative}")
        return ""
    return path.read_text(encoding="utf-8")


root_gradle = read("build.gradle")
core_gradle = read("TMessagesProj/build.gradle")
standalone_gradle = read("TMessagesProj_AppStandalone/build.gradle")
gradle_properties = read("gradle.properties")
main_manifest = read("TMessagesProj/src/main/AndroidManifest.xml")
standalone_manifest = read("TMessagesProj/config/release/AndroidManifest_standalone.xml")
provider_paths = read("TMessagesProj/src/main/res/xml/provider_paths.xml")
message_object = read("TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java")
container_manager = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramContainerManager.java")
secure_store = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramSecureStore.java")
push_controller = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramPushController.java")
network_controller = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramNetworkController.java")
deleted_media_store = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramDeletedMediaStore.java")
messages_storage = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
file_loader = read("TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java")
image_loader = read("TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java")
auto_delete_media = read("TMessagesProj/src/main/java/org/telegram/messenger/AutoDeleteMediaTask.java")
cache_control = read("TMessagesProj/src/main/java/org/telegram/ui/CacheControlActivity.java")
photo_viewer = read("TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java")
audio_player_cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/AudioPlayerCell.java")
video_player = read("TMessagesProj/src/main/java/org/telegram/ui/Components/VideoPlayer.java")
container_setup = read("TMessagesProj/src/main/java/org/telegram/ui/AgramContainerSetupActivity.java")
messages_controller = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
stories_controller = read("TMessagesProj/src/main/java/org/telegram/ui/Stories/StoriesController.java")
chat_activity = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
launch_activity = read("TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java")
application_loader = read("TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java")
user_config = read("TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java")
intro_activity = read("TMessagesProj/src/main/java/org/telegram/ui/IntroActivity.java")
native_config = read("TMessagesProj/jni/tgnet/Config.cpp")
native_connections = read("TMessagesProj/jni/tgnet/ConnectionsManager.cpp")
java_connections = read("TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java")
standalone_app_manifest = read("TMessagesProj_AppStandalone/src/main/AndroidManifest.xml")

for label, source in (("core", core_gradle), ("standalone", standalone_gradle)):
    if not re.search(r"minSdkVersion\s+26\b", source):
        errors.append(f"{label} module must keep minSdkVersion 26")

if not re.search(r"(?m)^APP_VERSION_NAME=12\.10\.1-a-gram\.24\s*$", gradle_properties):
    errors.append("Android overlay version must be 12.10.1-a-gram.24")

if not re.search(r'buildConfigField\s+"int",\s*"TELEGRAM_APP_ID",\s*configuredApiId\s*\?\s*configuredApiId\s*:\s*"0"', core_gradle):
    errors.append("production API ID must not have a public fallback")
if 'configuredApiHash ? configuredApiHash : ""' not in core_gradle:
    errors.append("production API hash must not have a public fallback")
if "checkAgramApiCredentials" not in core_gradle:
    errors.append("mandatory production credential check is missing")

if "org.unifiedpush.android:connector" in core_gradle:
    errors.append("external UnifiedPush connector dependency returned")
if "org.unifiedpush.android.distributor" in standalone_app_manifest:
    errors.append("external UnifiedPush distributor intents returned")
required_embedded_push_guards = (
    "PUSH_AGRAM",
    "byte[] random = new byte[32]",
    "containerId.equals(current.id)",
    "endpoint.equals(current.agramPushEndpoint)",
    "req.token_type = 4",
)
embedded_push_sources = container_manager + push_controller + messages_controller
for guard in required_embedded_push_guards:
    if guard not in embedded_push_sources:
        errors.append(f"embedded per-container Agram Push guard is missing: {guard}")

tor_named_patterns = (
    "**/AgramTor*.java",
    "**/AgramBridge*.java",
    "**/AgramRdsys*.java",
)
for pattern in tor_named_patterns:
    for path in ANDROID.glob(pattern):
        errors.append(f"embedded Tor source returned: {path.relative_to(ANDROID)}")

tor_dependency_markers = (
    "org.torproject",
    "guardianproject",
    "tor-android",
    "IPtProxy",
)
for marker in tor_dependency_markers:
    if marker in root_gradle or marker in core_gradle or marker in standalone_gradle:
        errors.append(f"embedded Tor dependency returned: {marker}")

production_sources = "\n".join(
    path.read_text(encoding="utf-8", errors="ignore")
    for path in ANDROID.rglob("*")
    if path.is_file() and path.suffix.lower() in {".java", ".xml", ".gradle", ".properties"}
)
if "TorService" in production_sources:
    errors.append("TorService reference returned to the no-Tor overlay")
# The word boundaries intentionally allow the private LEGACY_NETWORK_TOR
# migration symbol while rejecting a live NETWORK_TOR mode or reference.
if re.search(r"\bNETWORK_TOR\b", production_sources):
    errors.append("active NETWORK_TOR mode returned to the no-Tor overlay")

if not re.search(r"private\s+static\s+final\s+int\s+SCHEMA_VERSION\s*=\s*7\s*;", container_manager):
    errors.append("container metadata schema must be version 7")
if not re.search(r'private\s+static\s+final\s+String\s+LEGACY_NETWORK_TOR\s*=\s*"tor"\s*;', container_manager):
    errors.append("the private one-time legacy Tor migration literal is missing")
if not re.search(r"boolean\s+migratedFromTor\s*=\s*LEGACY_NETWORK_TOR\.equals\(", container_manager):
    errors.append("schema 7 does not detect a legacy Tor container")
legacy_migration = re.search(
    r"if\s*\(\s*migratedFromTor\s*\)\s*\{(?P<body>.{0,1800}?)\}",
    container_manager,
    re.DOTALL,
)
if legacy_migration is None:
    errors.append("schema 7 does not retire legacy Tor route fields")
else:
    migration_body = legacy_migration.group("body")
    if "NETWORK_DIRECT" not in migration_body:
        errors.append("legacy Tor containers must migrate to direct networking")
    for field in ("proxyAddress", "proxyPort", "proxyUsername", "proxyPassword", "proxySecret"):
        if field not in migration_body:
            errors.append(f"legacy Tor migration must clear obsolete field: {field}")
if not re.search(
    r"LEGACY_NETWORK_TOR\.equals\(json\.optString\(\"proxy_mode\".*?saveRecord\(record\)",
    container_manager,
    re.DOTALL,
):
    errors.append("the schema-7 legacy route migration must be persisted")

required_no_tor_route_guards = (
    (network_controller, "NETWORK_DIRECT"),
    (network_controller, "NETWORK_PROXY"),
    (network_controller, "native_setProxySettings"),
    (push_controller, "onNetworkRouteChanged"),
    (push_controller, "NETWORK_DIRECT"),
    (push_controller, "NETWORK_PROXY"),
    (push_controller, "proxySecret"),
)
for source, guard in required_no_tor_route_guards:
    if guard not in source:
        errors.append(f"direct/custom-proxy route guard is missing: {guard}")
if not re.search(r"HTTPS|HttpsURLConnection", push_controller):
    errors.append("Agram Push must keep its HTTPS transport boundary")
if not re.search(r"proxySecret.*(?:throw new IOException|cannot carry)|(?:throw new IOException|cannot carry).*proxySecret", push_controller, re.DOTALL):
    errors.append("MTProto proxies must not silently bypass the Agram Push HTTPS route")

if "ensureUniquePushInstanceLocked" not in container_manager:
    errors.append("legacy duplicate push instances are not repaired")
if "PUSH_INSTANCE_HASH_PREFIX" not in container_manager or "readPushInstance" in container_manager:
    errors.append("push identity checks must not decrypt every container")
if "purgeOrphanedContainers()" in application_loader:
    errors.append("cold start must not infer logout and delete containers")
if "deleteContainer(currentAccount)" not in messages_controller:
    errors.append("confirmed logout container cleanup is missing")

required_account_selection_guards = (
    (user_config, "ACCOUNT_SELECTION_PREFERENCES"),
    (user_config, "setSelectedAccountPersisted"),
    (user_config, "reconcileSelectedAccount"),
    (user_config, "getLoginTargetAccount"),
    (application_loader, "UserConfig.reconcileSelectedAccount()"),
    (intro_activity, "UserConfig.getLoginTargetAccount()"),
)
for source, guard in required_account_selection_guards:
    if guard not in source:
        errors.append(f"durable account-selection guard is missing: {guard}")
if re.search(r"UserConfig\.selectedAccount\s*=\s*[^=]", launch_activity + messages_controller + container_setup + intro_activity):
    errors.append("selected account must be changed through the synchronous registry")

required_session_persistence_guards = (
    (native_config, "isValidConfigFile"),
    (native_config, "configValid && backupValid"),
    (native_config, "remove(backupPath.c_str())"),
    (native_connections, "LogoutReasonLocalConfigMismatch"),
    (native_connections, "localAuthConfigQuarantined"),
    (java_connections, "LogoutReasonServerAuthRejected"),
    (java_connections, "sessionAuthConfigMismatch"),
    (secure_store, "getExistingKey(scope)"),
    (secure_store, "KeyUnavailableException"),
    (container_manager, "STORAGE_QUARANTINED"),
    (container_manager, "Refusing to overwrite unavailable Agram container"),
)
for source, guard in required_session_persistence_guards:
    if guard not in source:
        errors.append(f"session-preservation guard is missing: {guard}")
if "Cipher.DECRYPT_MODE, getOrCreateKey(scope)" in secure_store:
    errors.append("decrypt must never create a replacement Keystore key")

for label, manifest in (("main", main_manifest), ("standalone", standalone_manifest)):
    if 'android:allowBackup="false"' not in manifest or 'android:fullBackupContent="false"' not in manifest:
        errors.append(f"{label} manifest must disable Android backup")

banned_permissions = (
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_PHONE_NUMBERS",
    "android.permission.READ_CALL_LOG",
    "android.permission.SEND_SMS",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
)
for permission in banned_permissions:
    if permission in main_manifest or permission in standalone_manifest:
        errors.append(f"high-risk permission returned: {permission}")

required_retention_guards = (
    "!DialogObject.isEncryptedDialog(dialogId)",
    "message.ttl == 0",
    "message.ttl_period == 0",
    "!isSecretMedia(message)",
    "!isEphemeral(message)",
)
for guard in required_retention_guards:
    if guard not in message_object:
        errors.append(f"ordinary-message retention safety guard is missing: {guard}")

required_deleted_media_store_guards = (
    "deleted_media",
    "DispatchQueue",
    "postRunnable",
    ".sync()",
    "getContainer(account)",
    "containerId",
    "message.dialog_id",
    "message.id",
    "isCurrentContainer(",
    "snapshot.containerId.equals(current.id)",
    "!message.noforwards",
    "canKeepDeletedOnServer",
)
for guard in required_deleted_media_store_guards:
    if guard not in deleted_media_store:
        errors.append(f"persistent deleted-media archive guard is missing: {guard}")
archive_temporary = re.search(
    r'File\s+temporary\s*=\s*new\s+File\s*\(\s*parent\s*,\s*"\.agram-part-"\s*'
    r'\+\s*UUID\.randomUUID\(\)\s*\+\s*"\.tmp"\s*\)\s*;',
    deleted_media_store,
)
if archive_temporary is None:
    errors.append("deleted-media copies must use a unique temporary file beside the archive destination")
else:
    archive_copy_start = archive_temporary.start()
    file_sync_index = deleted_media_store.find("output.getFD().sync()", archive_copy_start)
    current_container_index = deleted_media_store.find("isCurrentContainer(request.snapshot)", file_sync_index)
    archive_move_index = deleted_media_store.find("moveAtomically(temporary, request.destination)", file_sync_index)
    directory_sync_index = deleted_media_store.find("syncDirectory(parent)", archive_move_index)
    archive_commit_order = (file_sync_index, current_container_index, archive_move_index, directory_sync_index)
    if any(index < 0 for index in archive_commit_order) or archive_commit_order != tuple(sorted(archive_commit_order)):
        errors.append("deleted-media temporary files must be synced, ownership-checked, atomically moved and directory-synced")
uses_captured_id_directory = re.search(
    r"getContainerDirectoryForId\s*\(\s*(?:record\.id|snapshot\.containerId|containerId)\s*\)",
    deleted_media_store,
) is not None
uses_canonical_container_root = all(
    guard in deleted_media_store
    for guard in ("getFilesDir()", '"agram_containers"', "record.id", "getCanonicalFile()", "getParentFile()")
)
if not (uses_captured_id_directory or uses_canonical_container_root):
    errors.append("deleted-media archives must derive a validated deterministic directory from the captured container id")
uses_atomic_move = "Files.move(" in deleted_media_store and "StandardCopyOption.ATOMIC_MOVE" in deleted_media_store
uses_rename_commit = "renameTo(" in deleted_media_store
if not (uses_atomic_move or uses_rename_commit):
    errors.append("a synced .part archive must be committed by atomic move or rename")

deleted_hook = re.search(
    r"markMessagesDeletedOnServer\s*\([^)]*\).*?(?=\n\s*(?:public|private|protected)\s)",
    messages_storage,
    re.DOTALL,
)
if deleted_hook is None or "AgramDeletedMediaStore" not in deleted_hook.group(0):
    errors.append("server-deletion handling must enqueue eligible media for persistent archival")
if "AgramDeletedMediaStore" not in file_loader or "getArchivedFile(" not in file_loader:
    errors.append("FileLoader must prefer the archived path for retained deleted media")
if not all(
    guard in image_loader
    for guard in ("AgramDeletedMediaStore", "getArchivedFileForImage(", "thumb == 0", "cacheFile = archivedFile")
):
    errors.append("ImageLoader must resolve retained deleted media through the persistent archive")
if "getPathToAttachForMessage(" not in video_player:
    errors.append("VideoPlayer must resolve documents through the archive-aware message path")
if not re.search(
    r'<files-path\b(?=[^>]*\bname="agram_deleted_media")(?=[^>]*\bpath="agram_containers/")[^>]*/?>',
    provider_paths,
):
    errors.append("FileProvider must expose archived media through the scoped app-private container subtree")
pending_cleanup_calls = re.compile(
    r"AgramDeletedMediaStore\.(?:isPendingSource|deleteOrDefer)\s*\("
)
if pending_cleanup_calls.search(cache_control) is None:
    errors.append("cache cleanup must protect a deleted-media source while its archive copy is pending")
if len(pending_cleanup_calls.findall(auto_delete_media)) < 2:
    errors.append("both age- and size-based automatic cache eviction must protect pending archive sources")
delete_files = re.search(
    r"public\s+void\s+deleteFiles\s*\([^)]*\).*?(?=\n\s*public\s)",
    file_loader,
    re.DOTALL,
)
if delete_files is None or pending_cleanup_calls.search(delete_files.group(0)) is None:
    errors.append("FileLoader.deleteFiles must not remove a pending archive source")
action_click = re.search(
    r"private\s+void\s+onActionClick\s*\(boolean\s+download\).*?(?=\n\s*private\s)",
    photo_viewer,
    re.DOTALL,
)
if action_click is None:
    errors.append("PhotoViewer action resolver is missing")
else:
    action_body = action_click.group(0)
    uses_message_aware_document_path = "getPathToAttachForMessage(currentMessageObject.messageOwner" in action_body
    archive_path_index = action_body.find("getPathToMessage(currentMessageObject.messageOwner")
    document_path_index = action_body.find("getPathToAttach(original")
    archive_first = archive_path_index >= 0 and (document_path_index < 0 or archive_path_index < document_path_index)
    if not (uses_message_aware_document_path or archive_first):
        errors.append("PhotoViewer must try the archive-aware message path before a document cache path")
audio_button_state = re.search(
    r"public\s+void\s+updateButtonState\s*\(boolean\s+ifSame\s*,\s*boolean\s+animated\s*\)"
    r".*?(?=\n\s*(?:@Override|public|private|protected)\s)",
    audio_player_cell,
    re.DOTALL,
)
if audio_button_state is None:
    errors.append("AudioPlayerCell state resolver is missing")
else:
    audio_state_body = audio_button_state.group(0)
    audio_message_path_index = audio_state_body.find("getPathToMessage(currentMessageObject.messageOwner")
    audio_document_path_index = audio_state_body.find("getPathToAttach(currentMessageObject.getDocument")
    uses_audio_message_attach = "getPathToAttachForMessage(currentMessageObject" in audio_state_body
    archive_first_audio = audio_message_path_index >= 0 and (
        audio_document_path_index < 0 or audio_message_path_index < audio_document_path_index
    )
    if not (uses_audio_message_attach or archive_first_audio):
        errors.append("AudioPlayerCell must resolve retained audio through its archive-aware message path")
    if "cacheFile.delete()" in audio_state_body or (
        "cacheFile.length() == 0" in audio_state_body
        and "AgramDeletedMediaStore.deleteOrDefer(cacheFile)" not in audio_state_body
    ):
        errors.append("AudioPlayerCell zero-length cleanup must not bypass pending archive protection")
container_delete = re.search(
    r"public\s+void\s+deleteContainer\s*\(int\s+account\).*?(?=\n\s*public\s)",
    container_manager,
    re.DOTALL,
)
durable_tombstone_delete = container_delete is not None and all(
    guard in container_delete.group(0)
    for guard in (
        "DELETION_INTENT_PREFIX",
        "getValidatedContainerChild(id, false)",
        'getValidatedContainerChild(".deleting-" + id, true)',
        "moveAtomically(containerDirectory, tombstone)",
        "syncDirectory(tombstone.getParentFile())",
        "deleteTombstone(tombstone)",
    )
)
if not durable_tombstone_delete:
    errors.append("confirmed container removal must first move all persistent data into a durable deletion tombstone")
if container_delete is None or not re.search(
    r"AgramDeletedMediaStore\.purgeContainer\s*\(\s*account\s*,\s*id\s*\)",
    container_delete.group(0),
):
    errors.append("container removal must release pending deleted-media archive work")
if durable_tombstone_delete:
    delete_body = container_delete.group(0)
    intent_index = delete_body.find("putBoolean(DELETION_INTENT_PREFIX + id, true)")
    move_index = delete_body.find("moveAtomically(containerDirectory, tombstone)")
    purge_index = delete_body.find("AgramDeletedMediaStore.purgeContainer")
    key_index = delete_body.find("AgramSecureStore.deleteKey(id)")
    registry_index = delete_body.find(".remove(SLOT_PREFIX + account)")
    delete_index = delete_body.find("deleteTombstone(tombstone)")
    lifecycle_indices = (intent_index, move_index, purge_index, key_index, registry_index, delete_index)
    if (
        any(index < 0 for index in lifecycle_indices)
        or intent_index >= min(move_index, purge_index)
        or max(move_index, purge_index) >= min(key_index, registry_index)
        or max(key_index, registry_index) >= delete_index
    ):
        errors.append("archive work and container data must be retired before credentials and the deletion tombstone")
if durable_tombstone_delete:
    validated_tombstone_delete = (
        "deleteRecursively(validated)" in container_manager
        or "deleteRecursively(validated, validated)" in container_manager
    )
    if not validated_tombstone_delete or not all(
        guard in container_manager
        for guard in (
            "sweepContainerTombstones();",
            "isStrictUuid(",
            "preferences.getAll()",
            "clearDeletionIntent(id)",
        )
    ):
        errors.append("crash-safe container tombstones must be validated and swept on the next start")

required_ghost_hooks = (
    (messages_controller, "shouldSuppressTyping(currentAccount)"),
    (messages_controller, "shouldSuppressReadReceipt(currentAccount)"),
    (messages_controller, "shouldMinimizeOnline(currentAccount)"),
    (stories_controller, "shouldSuppressStoryViews(currentAccount)"),
    (chat_activity, "markReadForGhostInteraction()"),
    (chat_activity, 'setTitle("Ghost Mode")'),
    (container_setup, 'sectionLabel(context, "GHOST MODE")'),
)
for source, hook in required_ghost_hooks:
    if hook not in source:
        errors.append(f"Ghost Mode hook is missing: {hook}")

for removed_false_code_hook in ("resolvePinTarget", "Legend target", "ложн"):
    if removed_false_code_hook in container_manager or removed_false_code_hook in launch_activity:
        errors.append(f"removed false-code feature returned: {removed_false_code_hook}")

tracked_text = "\n".join(
    path.read_text(encoding="utf-8", errors="ignore")
    for path in ANDROID.rglob("*")
    if path.is_file() and path.suffix.lower() in {".java", ".xml", ".gradle", ".properties", ".md", ".yml", ".yaml"}
)
if re.search(r"(?i)TELEGRAM_API_HASH\s*[=:]\s*[\"']?[0-9a-f]{32}[\"']?", tracked_text):
    errors.append("a concrete Telegram API hash appears in the public overlay")
if re.search(r"(?i)(storePassword|keyPassword)\s*[=:]\s*[\"']?(?!<|\$|System\.getenv)[^\s\"']{6,}", tracked_text):
    errors.append("a signing password may be hard-coded in the public overlay")
if re.search(r"(?im)^RELEASE_(?:STORE|KEY)_PASSWORD\s*=\s*\S+", tracked_text):
    errors.append("a signing password property appears in the public overlay")

if errors:
    print("Agram overlay security verification failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("Agram Android overlay security verification passed.")
