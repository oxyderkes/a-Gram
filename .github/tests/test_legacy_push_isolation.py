"""Legacy push regression: execute production method bodies with a fake preference store.

This intentionally needs no Android SDK, network, account or signed package. The
source/manifest checks complement the JVM harness; they do not replace a merged
manifest audit or notification delivery test on a device.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android"
JAVA = ANDROID / "TMessagesProj/src/main/java/org/telegram"


def source(name):
    return (JAVA / name).read_text(encoding="utf-8")


def method(text, declaration):
    start = text.index(declaration)
    opening = text.index("{", start)
    depth = 1
    cursor = opening + 1
    while depth:
        depth += (text[cursor] == "{") - (text[cursor] == "}")
        cursor += 1
    return text[start:cursor]


def java_tool(name):
    candidate = Path(os.environ.get("JAVA_HOME", "")) / "bin" / (name + (".exe" if os.name == "nt" else ""))
    found = str(candidate) if candidate.is_file() else shutil.which(name)
    if not found:
        raise RuntimeError(f"JDK required: {name} not found (set JAVA_HOME)")
    return found


class LegacyPushIsolationTest(unittest.TestCase):
    def test_old_callbacks_and_cleanup_execute_safely(self):
        push = source("messenger/PushListenerController.java")
        shared = source("messenger/SharedConfig.java")
        messages = source("messenger/MessagesController.java")
        connections = source("tgnet/ConnectionsManager.java")
        gcm = source("messenger/GcmPushListenerService.java")
        bodies = {
            "PUSH_METHODS": "\n".join(method(push, signature) for signature in (
                "public static void sendRegistrationToServer(",
                "public static void processRemoteMessage(",
                "public void onRequestPushToken(",
            )),
            "SHARED_METHODS": "\n".join(method(shared, signature) for signature in (
                "private static SharedPreferences.Editor removeLegacyPushPreferences(",
                "public static void clearLegacyPushState(",
            )),
            "MESSAGE_METHODS": "\n".join(method(messages, signature) for signature in (
                "public void unregistedPush(", "public void registerForPush(",
            )),
            "CONNECTION_METHODS": "\n".join(method(connections, signature) for signature in (
                "private String getRegId(", "public static void setRegId(",
            )),
            "GCM_METHOD": method(gcm, "public void onNewToken("),
        }
        harness = r'''
import java.util.*;
public class LegacyPushRuntimeTest {
  static final String SECRET = "old-token-never-log";
  static class SharedPreferences {
    Map<String,Object> values = new HashMap<>();
    boolean failCommit;
    Map<String,?> getAll() { return new HashMap<>(values); }
    Editor edit() { return new Editor(); }
    class Editor {
      Set<String> removals = new HashSet<>();
      Editor remove(String key) { removals.add(key); return this; }
      boolean commit() {
        if (failCommit) return false;
        removals.forEach(values::remove);
        return true;
      }
    }
  }
  static class Context {
    static final int MODE_PRIVATE = 0;
    Map<String,SharedPreferences> stores = new HashMap<>();
    boolean failRead;
    SharedPreferences getSharedPreferences(String name, int mode) {
      if (failRead) throw new IllegalStateException(SECRET);
      return stores.computeIfAbsent(name, ignored -> new SharedPreferences());
    }
  }
  static class ApplicationLoader { static Context applicationContext; }
  static class FileLog {
    static List<String> logs = new ArrayList<>();
    static void e(String message) { logs.add(message); }
  }
  static class PushListenerController {
    @interface PushType { }
    static final int PUSH_TYPE_FIREBASE = 2;
    PUSH_METHODS
  }
  static class GcmPushListenerService { GCM_METHOD }
  static class SharedConfig {
    static final Object sync = new Object();
    static String pushString, pushStringStatus;
    static int pushType;
    static boolean pushStatSent;
    static long pushStringGetTimeStart, pushStringGetTimeEnd;
    static byte[] pushAuthKey, pushAuthKeyId;
    SHARED_METHODS
  }
  static class MessagesController {
    static class UserConfig { boolean registeredForPush = true; }
    UserConfig currentUser = new UserConfig();
    UserConfig getUserConfig() { return currentUser; }
    boolean registeringForPush = true;
    MESSAGE_METHODS
  }
  static class ConnectionsManager { CONNECTION_METHODS }
  static void seedMemory() {
    SharedConfig.pushString = SharedConfig.pushStringStatus = SECRET;
    SharedConfig.pushAuthKey = SharedConfig.pushAuthKeyId = new byte[] {1, 2};
    SharedConfig.pushStatSent = true;
    SharedConfig.pushStringGetTimeStart = SharedConfig.pushStringGetTimeEnd = 123;
  }
  static void assertMemoryEmpty() {
    assert SharedConfig.pushString.isEmpty();
    assert SharedConfig.pushStringStatus.isEmpty();
    assert SharedConfig.pushAuthKey == null && SharedConfig.pushAuthKeyId == null;
    assert !SharedConfig.pushStatSent;
    assert SharedConfig.pushStringGetTimeStart == 0 && SharedConfig.pushStringGetTimeEnd == 0;
  }
  public static void main(String[] args) {
    seedMemory();
    SharedConfig.clearLegacyPushState(); // also safe before Application.onCreate
    assertMemoryEmpty();
    ApplicationLoader.applicationContext = new Context();
    Context context = ApplicationLoader.applicationContext;
    SharedPreferences prefs = context.getSharedPreferences("userconfing", 0);
    String[] obsolete = {"pushString", "pushString2", "pushStringStatus", "pushType", "pushStatSent",
      "pushAuthKey", "pushAuthKeyId", "pushStringGetTimeStart", "pushStringGetTimeEnd"};
    for (String key : obsolete) prefs.values.put(key, SECRET);
    prefs.values.put("passcodeHash1", "keep-lock");
    prefs.values.put("agramPushEndpoint", "keep-container-endpoint");
    SharedPreferences cache = context.getSharedPreferences("com.google.android.gms.appid", 0);
    cache.values.put("|T|sender|*", SECRET);
    cache.values.put("subtype|T|sender|*", SECRET);
    cache.values.put("unrelated", "keep");
    SharedPreferences mlkit = context.getSharedPreferences("com.google.mlkit", 0);
    mlkit.values.put("model", "keep-model");
    seedMemory();
    SharedConfig.clearLegacyPushState();
    SharedConfig.clearLegacyPushState(); // idempotent cold-start migration
    assertMemoryEmpty();
    for (String key : obsolete) assert !prefs.values.containsKey(key) : key;
    assert prefs.values.size() == 2;
    assert cache.values.size() == 1 && cache.values.containsKey("unrelated");
    assert mlkit.values.get("model").equals("keep-model");
    // A failed disk write must never restore sensitive values into memory.
    prefs.values.put("pushString2", SECRET);
    prefs.failCommit = true;
    seedMemory();
    SharedConfig.clearLegacyPushState();
    assertMemoryEmpty();
    assert prefs.values.containsKey("pushString2");
    prefs.failCommit = false;
    SharedConfig.clearLegacyPushState();
    assert !prefs.values.containsKey("pushString2");
    context.failRead = true;
    seedMemory();
    SharedConfig.clearLegacyPushState();
    assertMemoryEmpty();
    context.failRead = false;
    // Stale token/status/message paths must not persist or dispatch anything.
    for (int type : new int[] {2, 13, -1}) {
      PushListenerController.sendRegistrationToServer(type, SECRET);
      PushListenerController.sendRegistrationToServer(type, null);
      PushListenerController.processRemoteMessage(type, SECRET, Long.MAX_VALUE);
      ConnectionsManager.setRegId(SECRET, type, SECRET);
      MessagesController account = new MessagesController();
      account.registerForPush(type, SECRET);
      account.unregistedPush();
      assert !account.currentUser.registeredForPush && !account.registeringForPush;
    }
    new PushListenerController().onRequestPushToken();
    new GcmPushListenerService().onNewToken(SECRET);
    assert new ConnectionsManager().getRegId().isEmpty();
    assertMemoryEmpty();
    for (String log : FileLog.logs) assert !log.contains(SECRET);
    System.out.println("Legacy push callbacks, cleanup, failure and retry regressions passed");
  }
}
'''
        for marker, body in bodies.items():
            harness = harness.replace(marker, body)
        with tempfile.TemporaryDirectory(prefix="agram-legacy-push-") as temporary:
            target = Path(temporary)
            java = target / "LegacyPushRuntimeTest.java"
            java.write_text(harness, encoding="utf-8")
            subprocess.run([java_tool("javac"), "-encoding", "UTF-8", "--release", "8", "-d", str(target), str(java)], check=True)
            subprocess.run([java_tool("java"), "-ea", "-cp", str(target), "LegacyPushRuntimeTest"], check=True, timeout=30)

    def test_native_cannot_serialize_shared_device_token(self):
        text = (ANDROID / "TMessagesProj/jni/tgnet/ConnectionsManager.cpp").read_text(encoding="utf-8")
        self.assertNotIn('objectValue->key = "device_token"', text)
        self.assertNotRegex(text, r"currentRegId\s*=\s*regId")
        self.assertNotIn("scheduleTask", method(text, "void ConnectionsManager::setRegId("))

    def test_simple_push_stays_per_container(self):
        text = source("messenger/MessagesController.java")
        self.assertNotIn("other_uids.add", text)
        for signature in ("public void registerAgramPush(", "public void unregisterAgramPush("):
            body = method(text, signature)
            self.assertIn("req.token_type = 4", body)
            self.assertIn("req.token = endpoint", body)
            self.assertNotIn("SharedConfig.push", body)

    def test_shared_persistence_cannot_reload_or_resave_token(self):
        text = source("messenger/SharedConfig.java")
        self.assertIn("clearLegacyPushState();", method(text, "public static void loadConfig("))
        for key in ("pushString2", "pushAuthKey", "pushType", "pushStatSent"):
            self.assertNotRegex(text, rf'(?:get|put)(?:String|Int|Boolean)\("{key}"')
        self.assertIn("removeLegacyPushPreferences(editor);", method(text, "public static void saveConfig("))

    def test_effective_flavor_manifest_removes_legacy_components(self):
        android = "{http://schemas.android.com/apk/res/android}"
        tools = "{http://schemas.android.com/tools}"
        manifest = ET.parse(ANDROID / "TMessagesProj/config/release/AndroidManifest_standalone.xml").getroot()
        app = manifest.find("application")
        for tag, name in (
            ("service", "org.telegram.messenger.GcmPushListenerService"),
            ("service", "com.google.firebase.messaging.FirebaseMessagingService"),
            ("receiver", "com.google.firebase.iid.FirebaseInstanceIdReceiver"),
            ("provider", "com.google.firebase.provider.FirebaseInitProvider"),
        ):
            matches = [node for node in app.findall(tag) if node.get(android + "name") == name]
            self.assertEqual(len(matches), 1, name)
            self.assertEqual(matches[0].get(tools + "node"), "remove", name)
        self.assertFalse(any("MlKit" in node.get(android + "name", "") and node.get(tools + "node") == "remove" for node in app))
        auto_init = next(node for node in app.findall("meta-data") if node.get(android + "name") == "firebase_messaging_auto_init_enabled")
        self.assertEqual(auto_init.get(android + "value"), "false")

    def test_dependency_graph_excludes_messaging_only(self):
        for module in ("TMessagesProj", "TMessagesProj_AppStandalone"):
            gradle = (ANDROID / module / "build.gradle").read_text(encoding="utf-8")
            self.assertIn("exclude group: 'com.google.firebase', module: 'firebase-messaging'", gradle)
            self.assertIn("exclude group: 'com.google.firebase', module: 'firebase-iid'", gradle)
            self.assertNotRegex(gradle, r"implementation\s+['\"]com\.google\.firebase:firebase-messaging:")
            self.assertNotIn("exclude group: 'com.google.mlkit'", gradle)
        self.assertNotIn("import com.google.firebase", source("messenger/PushListenerController.java"))
        self.assertNotIn("import com.google.firebase", source("messenger/GcmPushListenerService.java"))

    def test_background_simple_push_does_not_recreate_retired_container(self):
        text = source("messenger/AgramPushController.java")
        self.assertNotIn(".ensureContainer(", text)
        unregister = method(text, "public void unregisterAccount(int account, String expectedContainerId,")
        self.assertIn("subscription.containerId.equals(expectedContainerId)", unregister)
        self.assertIn("runBoundSettingsUpdate(account, expectedContainerId", unregister)
        self.assertIn("catch (RuntimeException error)", unregister)
        self.assertIn("AgramPushState.clear(account, expectedContainerId)", unregister)
        self.assertIn("isSessionGenerationCurrent(sessionGeneration)", method(text, "private boolean isCurrentBinding("))
        for signature in ("private void readStream(", "private void handleLine("):
            self.assertIn("if (!stopped && isCurrentBinding())", method(text, signature))


if __name__ == "__main__":
    unittest.main()
