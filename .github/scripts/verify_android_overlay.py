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


core_gradle = read("TMessagesProj/build.gradle")
standalone_gradle = read("TMessagesProj_AppStandalone/build.gradle")
main_manifest = read("TMessagesProj/src/main/AndroidManifest.xml")
standalone_manifest = read("TMessagesProj/config/release/AndroidManifest_standalone.xml")
message_object = read("TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java")
container_manager = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramContainerManager.java")
push_controller = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramPushController.java")
network_controller = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramNetworkController.java")
tor_manager = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramTorManager.java")
session_route = read("TMessagesProj/src/main/java/org/telegram/messenger/AgramSessionRouteController.java")
container_setup = read("TMessagesProj/src/main/java/org/telegram/ui/AgramContainerSetupActivity.java")
dialogs_activity = read("TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java")
messages_controller = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
stories_controller = read("TMessagesProj/src/main/java/org/telegram/ui/Stories/StoriesController.java")
chat_activity = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
launch_activity = read("TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java")
application_loader = read("TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java")
standalone_app_manifest = read("TMessagesProj_AppStandalone/src/main/AndroidManifest.xml")

for label, source in (("core", core_gradle), ("standalone", standalone_gradle)):
    if not re.search(r"minSdkVersion\s+26\b", source):
        errors.append(f"{label} module must keep minSdkVersion 26")

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

required_tor_guards = (
    (core_gradle, "com.netzarchitekten:IPtProxy:5.5.1"),
    (tor_manager, "IsolateSOCKSAuth"),
    (tor_manager, "AgramSecureStore.encrypt(BRIDGE_SCOPE"),
    (tor_manager, "BOOTSTRAP_TIMEOUT_MS"),
    (tor_manager, "pollBootstrap"),
    (tor_manager, "Utilities.globalQueue.postRunnable(() ->"),
    (network_controller, 'pause(account, "tor_starting")'),
    (network_controller, "rotateTorIsolation(account)"),
    (session_route, "TL_account.getAuthorizations"),
)
for source, guard in required_tor_guards:
    if guard not in source:
        errors.append(f"embedded Tor/session-route guard is missing: {guard}")

if "SocksPort 127.0.0.1:auto" in tor_manager:
    errors.append("invalid address:auto SocksPort syntax returned")
if 'SocksPort auto IsolateSOCKSAuth' not in tor_manager:
    errors.append("embedded Tor must request an automatic isolated SOCKS listener")
if "transportController.localAddress(transport)" not in tor_manager:
    errors.append("pluggable transport endpoint discovery is missing")
if ".append(endpoint).append(':').append(port)" in tor_manager or ".append(address).append(':').append(port)" in tor_manager:
    errors.append("IPtProxy localAddress already includes its port and must not be suffixed again")
if '.append(" socks5 ").append(endpoint).append(\'\\n\')' not in tor_manager:
    errors.append("pluggable transport endpoint must be written as the complete IPtProxy host:port value")
if "circuitBuilt || bootstrapProgress >= 100" in tor_manager or "circuitBuilt = true" in tor_manager:
    errors.append("TorService STATUS_ON must not be treated as completed Tor bootstrap")
if "port > 0 && port <= 65535 && bootstrapProgress >= 100" not in tor_manager:
    errors.append("Tor readiness must require a real 100 percent control-port bootstrap phase")
if "onTransportFailure" in tor_manager or "onTransportConnectionIssue" not in tor_manager:
    errors.append("individual pluggable-transport connection failures must remain retryable")
if "Tor must remain alive so it can try the next bridge" not in tor_manager:
    errors.append("bridge failover guard is missing")
if "agramRouteItem" in dialogs_activity or "100% · подключено" in dialogs_activity:
    errors.append("removed Tor status pill returned to the dialogs header")
if "agramNetworkItem" not in dialogs_activity or "AgramTorSettingsActivity(currentAccount)" not in dialogs_activity:
    errors.append("Tor/proxy settings icon is missing from the dialogs header")

if "ensureUniquePushInstanceLocked" not in container_manager:
    errors.append("legacy duplicate push instances are not repaired")
if "PUSH_INSTANCE_HASH_PREFIX" not in container_manager or "readPushInstance" in container_manager:
    errors.append("push identity checks must not decrypt every container")
if "purgeOrphanedContainers()" in application_loader:
    errors.append("cold start must not infer logout and delete containers")
if "deleteContainer(currentAccount)" not in messages_controller:
    errors.append("confirmed logout container cleanup is missing")

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
