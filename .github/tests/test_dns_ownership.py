"""Cross-language DNS ownership contracts; these checks perform no DNS or network I/O."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = (ROOT / "android/TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java").read_text(encoding="utf-8")
JNI = (ROOT / "android/TMessagesProj/jni/TgNetWrapper.cpp").read_text(encoding="utf-8")
SOCKET = (ROOT / "android/TMessagesProj/jni/tgnet/ConnectionSocket.cpp").read_text(encoding="utf-8")
HEADER = (ROOT / "android/TMessagesProj/jni/tgnet/ConnectionSocket.h").read_text(encoding="utf-8")


def section(text, begin, end):
    start = text.index(begin)
    return text[start:text.index(end, start + len(begin))]


def java_descriptor(name):
    match = re.search(r"public static (?:native )?void " + name + r"\(([^)]*)\)", JAVA)
    if match is None:
        raise AssertionError(f"missing Java method {name}")
    types = {"int": "I", "long": "J", "String": "Ljava/lang/String;"}
    parameters = [value.strip().split()[0] for value in match.group(1).split(",")]
    return "(" + "".join(types[value] for value in parameters) + ")V"


class DnsOwnershipContractTest(unittest.TestCase):
    def test_java_callback_matches_jni_lookup(self):
        descriptor = java_descriptor("getHostByName")
        native = re.search(r'GetStaticMethodID\(jclass_ConnectionsManager, "getHostByName", "([^"]+)"\)', JNI)
        self.assertIsNotNone(native)
        self.assertEqual(descriptor, native.group(1))
        self.assertEqual(descriptor, "(ILjava/lang/String;JJ)V", "account and request token are ABI requirements")
        delegate = section(JNI, "    void getHostByName(", "    int32_t getInitFlags(")
        self.assertIn("instanceNum, domainName", delegate)
        self.assertIn("socket->getHostResolveToken()", delegate)

    def test_native_completion_matches_java_and_registration(self):
        descriptor = java_descriptor("native_onHostNameResolved")
        native = re.search(r'\{"native_onHostNameResolved", "([^"]+)"', JNI)
        self.assertIsNotNone(native)
        self.assertEqual(descriptor, native.group(1))
        self.assertEqual(descriptor, "(ILjava/lang/String;JJLjava/lang/String;)V")
        callback = section(JNI, "void onHostNameResolved(", "void discardConnection(")
        self.assertIn("jint instanceNum", callback)
        self.assertIn("jlong token", callback)
        self.assertIn("ConnectionSocket::completeHostResolve(instanceNum, socket, (uint64_t) token", callback)
        self.assertNotIn("socket->", callback, "JNI must not dereference a pointer returned asynchronously")

    def test_native_liveness_precedes_dereference_and_open_token_rejects_reuse(self):
        complete = SOCKET[SOCKET.index("void ConnectionSocket::completeHostResolve("):]
        self.assertLess(complete.index("scheduleTask("), complete.index("manager.activeConnections.begin()"))
        self.assertLess(complete.index("== manager.activeConnections.end()"), complete.index("socket->hostResolveToken"))
        self.assertIn("socket->hostResolveToken != token", complete)
        self.assertIn("socket->waitingForHostResolve != host", complete)
        self.assertLess(complete.index("socket->hostResolveToken != token"), complete.index("socket->openConnectionInternal("))
        self.assertIn("static std::atomic<uint64_t> nextHostResolveToken", SOCKET)
        opened = section(SOCKET, "void ConnectionSocket::openConnection(", "void ConnectionSocket::openConnectionInternal(")
        self.assertIn("hostResolveToken = nextHostResolveToken.fetch_add(1)", opened)
        destructor = section(SOCKET, "ConnectionSocket::~ConnectionSocket()", "void ConnectionSocket::openConnection(")
        self.assertIn("detachConnection(this)", destructor)
        self.assertIn("uint64_t hostResolveToken = 0", HEADER)

    def test_proxy_bootstrap_has_exact_owner_and_no_http_fallback(self):
        owner = section(JAVA, "    private static final class DnsOwner", "    private static int lastClassGuid")
        for required in ("containerId.equals(current.id)", "isSessionGenerationCurrent(generation)",
                         "NETWORK_PROXY.equals(current.proxyMode)", "current.proxyAddress.trim().equalsIgnoreCase(host)"):
            self.assertIn(required, owner)
        callback = section(JAVA, "    public static void getHostByName(", "    public static void onBytesReceived(")
        self.assertLess(callback.index("DnsOwner.capture(currentAccount)"), callback.index("runOnUIThread("))
        self.assertIn('owner.key + ":" + hostName.toLowerCase(Locale.US)', callback)
        self.assertIn("dnsCache.get(key)", callback)
        self.assertIn("resolvingHostnameTasks.get(key)", callback)
        resolver = section(JAVA, "    private static class ResolveHostByNameTask", "    private static class GoogleDnsLoadTask")
        self.assertIn("InetAddress.getAllByName(currentHostName)", resolver)
        self.assertNotIn("new URL(", resolver)
        self.assertNotIn("openConnection(", resolver)
        self.assertNotIn("google.com", resolver)
        self.assertGreaterEqual(resolver.count("owner.permitsProxyBootstrap(currentHostName)"), 3)
        self.assertIn("current && result != null ? result.getAddress()", resolver)

    def test_direct_dns_tasks_keep_owner_through_completion_and_fallback(self):
        owner = section(JAVA, "    private static final class DnsOwner", "    private static int lastClassGuid")
        self.assertIn('account + ":" + containerId + ":" + generation', owner)
        self.assertIn("isDirectHttpOwnerCurrent(account, containerId, generation)", owner)
        request = section(JAVA, "    public static void onRequestNewServerIpAndPort(", "    public static void onProxyError(")
        self.assertLess(request.index("DnsOwner.capture(currentAccount)"), request.index("globalQueue.postRunnable("))
        self.assertIn("dnsConfigOwners[currentAccount]", request)
        self.assertIn("lastDnsRequestTimes[currentAccount]", request)
        self.assertNotRegex(JAVA, r"private static (?:AsyncTask currentTask|long lastDnsRequestTime;)")
        google = section(JAVA, "    private static class GoogleDnsLoadTask", "    private static class MozillaDnsLoadTask")
        mozilla = section(JAVA, "    private static class MozillaDnsLoadTask", "    public static long lastPremiumFloodWaitShown")
        for task in (google, mozilla):
            self.assertIn("private final DnsOwner owner", task)
            self.assertIn("currentAccount, downloadUrl, owner.containerId, owner.generation", task)
            finish = task[task.index("protected void onPostExecute("):]
            self.assertIn("currentDnsTasks[currentAccount] != this || !owner.permitsDirectHttp()", finish)
            self.assertLess(finish.index("!owner.permitsDirectHttp()"), finish.index("native_applyDnsConfig("))
            self.assertIn("if (result != null) result.reuse()", finish)
            self.assertIn("result.address, owner.phone, responseDate", finish)
        self.assertIn("if (!owner.permitsDirectHttp()) return", google)
        self.assertIn("new MozillaDnsLoadTask(owner)", google)


if __name__ == "__main__":
    unittest.main()
