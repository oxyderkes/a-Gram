"""JNI ABI and ownership-fence contracts; no native transport, account, or network access."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = (ROOT / "android/TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java").read_text(encoding="utf-8")
JNI = (ROOT / "android/TMessagesProj/jni/TgNetWrapper.cpp").read_text(encoding="utf-8")
NATIVE = (ROOT / "android/TMessagesProj/jni/tgnet/ConnectionsManager.cpp").read_text(encoding="utf-8")
CONTAINERS = (ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramContainerManager.java").read_text(encoding="utf-8")
MESSAGES = (ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java").read_text(encoding="utf-8")


def method(source, signature):
    """Return one balanced method, including nested queue lambdas."""
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 0
    for position in range(opening, len(source)):
        if source[position] == "{":
            depth += 1
        elif source[position] == "}":
            depth -= 1
            if depth == 0:
                return source[start:position + 1]
    raise AssertionError(f"unterminated source method: {signature}")


def descriptor(name):
    match = re.search(r"public static (?:native )?(\w+) " + name + r"\(([^)]*)\)", JAVA)
    if match is None:
        raise AssertionError(f"missing Java API {name}")
    types = {"void": "V", "int": "I", "long": "J", "boolean": "Z", "String": "Ljava/lang/String;"}
    parameters = [value.strip().split()[-2] for value in match.group(2).split(",") if value.strip()]
    return "(" + "".join(types[value] for value in parameters) + ")" + types[match.group(1)]


class NativeOwnerContractTest(unittest.TestCase):
    def test_init_appends_owner_to_existing_abi(self):
        java = descriptor("native_init")
        self.assertTrue(java.endswith("Ljava/lang/String;Z)V"), "container UUID and pending-retirement latch must reach synchronous native initialization")
        self.assert_registered("native_init", java)

    def test_owner_readiness_is_boolean_with_explicit_owner(self):
        self.assertEqual(descriptor("native_isContainerOwnerReady"), "(ILjava/lang/String;)Z")
        self.assert_registered("native_isContainerOwnerReady", descriptor("native_isContainerOwnerReady"))

    def test_transition_carries_expected_owner_target_and_correlation_id(self):
        expected = "(ILjava/lang/String;Ljava/lang/String;J)V"
        self.assertEqual(descriptor("native_transitionContainerOwner"), expected)
        self.assert_registered("native_transitionContainerOwner", expected)

    def test_transition_acknowledgement_callback_abi(self):
        expected = "(IJZ)V"
        self.assertEqual(descriptor("onNativeOwnerTransition"), expected)
        callback = re.search(r'GetStaticMethodID\(jclass_ConnectionsManager,\s*"onNativeOwnerTransition",\s*"([^"]+)"\)', JNI)
        self.assertIsNotNone(callback, "JNI must resolve the durable acknowledgement callback")
        self.assertEqual(callback.group(1), expected)

    def test_java_request_owner_is_captured_before_queue_and_checked_at_delivery(self):
        queued = method(JAVA, "public int sendRequest(final TLObject object, final RequestDelegate onComplete, final RequestDelegateTimestamp")
        self.assertLess(queued.index("captureRequestOwner("), queued.index("Utilities.stageQueue.postRunnable("))
        synchronous = method(JAVA, "public int sendRequestSync(")
        self.assertLess(synchronous.index("captureRequestOwner("), synchronous.index("sendRequestInternal("))
        dispatch = method(JAVA, "private void sendRequestInternal(")
        self.assertLess(dispatch.index("!owner.isCurrent()"), dispatch.index("object.serializeToStream("))
        self.assertGreaterEqual(dispatch.count("!owner.isCurrent()"), 4, "dispatch, response, queued delivery and final native admission need fences")
        self.assertIn("if (owner.isCurrent()) onQuickAck.run()", dispatch)
        self.assertIn("if (owner.isCurrent()) onWriteToSocket.run()", dispatch)
        self.assertIn("if (finalResponse != null) finalResponse.freeResources()", dispatch)
        owner = method(JAVA, "private final class RequestOwner")
        for required in ("final String containerId", "final long generation", "isSessionGenerationCurrent(generation)",
                         "record.isStorageAccessible()", "containerId.equals(record.id)", "native_isContainerOwnerReady(currentAccount, containerId)"):
            self.assertIn(required, owner)

    def test_startup_and_acknowledgement_cannot_recurse_singleton_construction(self):
        callback = method(JAVA, "public static void onNativeOwnerTransition(")
        self.assertIn("Instance[account]", callback)
        self.assertNotIn("getInstance(", callback)
        self.assertIn("if (existing == null) return", callback)
        startup = method(JAVA, "public void init(int version")
        self.assertLess(startup.index("native_init("), startup.index("native_isContainerOwnerReady("))
        self.assertLess(startup.index("native_isContainerOwnerReady("), startup.index("nativeOwner.initialize("))
        native_startup = method(NATIVE, "void ConnectionsManager::init(")
        self.assertLess(native_startup.index("loadConfig()"), native_startup.index("pthread_create("))
        quarantine = method(NATIVE, "void ConnectionsManager::quarantineContainerTransport()")
        self.assertNotIn("delegate->", quarantine, "startup error must be read synchronously, not call into unconstructed Java instance")

    def test_pending_retirement_is_latched_before_native_startup_traffic(self):
        startup = method(JAVA, "public void init(int version")
        self.assertLess(startup.index("containerRetirementPending = manager.hasPendingNativeRetirement("), startup.index("native_init("))
        self.assertIn("containerOwnerId, containerRetirementPending)", startup)
        native_startup = method(NATIVE, "void ConnectionsManager::init(")
        self.assertLess(native_startup.index("startupRetirementPending = retirementPending"), native_startup.index("loadConfig()"))
        load = method(NATIVE, "void ConnectionsManager::loadConfig()")
        self.assertIn("containerOwnerPaused.store(startupRetirementPending)", load)
        self.assertIn("ownerAction != AgramNativeOwnerPolicy::StartupAction::Keep", load,
                      "verified same-owner startup must not require rewriting durable auth on a full disk")

    def test_unreadable_persisted_identity_is_not_an_inactive_slot(self):
        startup = method(JAVA, "public void init(int version")
        guard = "userId != 0 || !getUserConfig().hasPersistedSession()"
        self.assertIn(guard, startup)
        self.assertLess(startup.index(guard), startup.index("containerOwnerId = record.id"))
        self.assertIn('String containerOwnerId = ""', startup,
                      "unverified persisted user must pass an empty owner and quarantine native keys")

    def test_ownerless_rejection_callback_also_keeps_original_generation(self):
        rejection = method(JAVA, "private void rejectUnavailableRequest(")
        self.assertLess(rejection.index("getSessionGeneration()"), rejection.index("Utilities.stageQueue.postRunnable("))
        self.assertLess(rejection.index("final String containerId = record.id"), rejection.index("Utilities.stageQueue.postRunnable("))
        self.assertLess(rejection.index("isSessionGenerationCurrent(generation)"), rejection.index("onComplete.run("))
        self.assertLess(rejection.index("isCurrentContainer(currentAccount, containerId)"), rejection.index("onComplete.run("))

    def test_handshake_requires_durable_keys_before_authorization_or_request_processing(self):
        handshake = method(NATIVE, "void ConnectionsManager::onDatacenterHandshakeComplete(")
        failure = method(handshake, "if (!saveConfig())")
        self.assertIn("quarantineContainerTransport()", failure)
        self.assertIn("delegate->onLogout(instanceNum, LogoutReasonLocalConfigMismatch)", failure)
        self.assertIn("return;", failure)
        for work in ("recreateSessions(type)", "clearRequestsForDatacenter(", "processRequestQueue(", "scheduleCheckProxyInternal("):
            self.assertLess(handshake.index("if (!saveConfig())"), handshake.index(work))
        quarantine = method(NATIVE, "void ConnectionsManager::quarantineContainerTransport()")
        self.assertIn("containerOwnerPaused.store(true)", quarantine)
        self.assertIn("suspendConnections(true)", quarantine)
        self.assertIn("getProxyConnection(", quarantine)
        for destructive in ("clearAuthKey(", "recreateSessions(", "saveConfig(", "retireContainerTransport(", ".clear()"):
            self.assertNotIn(destructive, quarantine, "local persistence failure must retain recoverable session material")

    def test_local_native_storage_diagnostic_survives_unready_transport_without_logout(self):
        logout = method(JAVA, "public static void onLogout(")
        self.assertIn("owner.isCurrent(reason != LogoutReasonLocalConfigMismatch)", logout)
        self.assertNotIn("captureCallbackOwner(", logout, "normal callback capture would drop the diagnostic after native quarantine")
        owner = method(JAVA, "private final class RequestOwner")
        self.assertIn("!checkNative || native_isContainerOwnerReady(currentAccount, containerId)", owner)
        local = method(logout, "if (reason == LogoutReasonLocalConfigMismatch)")
        self.assertIn("localAuthConfigQuarantined = true", local)
        self.assertIn("native_pauseNetwork(currentAccount)", local)
        self.assertIn("NotificationCenter.sessionAuthConfigMismatch", local)
        self.assertIn("return;", local)
        for destructive in ("performLogout(", "clearConfig(", "deleteContainer(", "native_cleanUp("):
            self.assertNotIn(destructive, local)

    def test_epoch_transition_retires_prelogin_work_and_acknowledges_after_persistence(self):
        transition = method(NATIVE, "void ConnectionsManager::transitionContainerOwner(")
        self.assertLess(transition.index("containerOwnerEpoch.fetch_add(1)"), transition.index("scheduleTask("))
        self.assertLess(transition.index("success = saveConfig(&newOwnerId)"), transition.index("containerOwnerId = newOwnerId"))
        self.assertLess(transition.index("containerOwnerId = newOwnerId"), transition.index("completion(success)"))
        self.assertIn("currentUserId == 0 || explicitLogout", transition)
        scheduler = method(NATIVE, "void ConnectionsManager::scheduleTask(")
        self.assertIn("epoch != containerOwnerEpoch.load()", scheduler)
        self.assertLess(scheduler.index("if (onRetired) onRetired()"), scheduler.index("task()"))
        retired = method(NATIVE, "void ConnectionsManager::retireContainerTransport()")
        for queue in ("waitingLoginRequests", "requestsQueue", "runningRequests", "proxyCheckQueue", "proxyActiveChecks"):
            self.assertIn(queue + ".clear()", retired)
        self.assertNotIn("RequestFlagWithoutLogin", retired, "pre-login work must not bypass retirement")
        self.assertIn("clearAuthKey(HandshakeTypeAll)", retired)

    def test_durable_logout_intent_and_native_ack_precede_container_deletion(self):
        prepare = MESSAGES.index("prepareNativeRetirement(")
        clear = MESSAGES.index("clearConfig(preserveBlockedAccount, leavingGeneration)")
        self.assertLess(prepare, clear)
        finished = method(JAVA, "private void finishNativeRetirement(")
        self.assertLess(finished.index("acknowledgeNativeRetirement("), finished.index("deleteContainer("))
        deletion = method(CONTAINERS, "public boolean deleteContainer(int account, String expectedId)")
        self.assertLess(deletion.index("canDeleteRetiredContainer(id)"), deletion.index("DELETION_INTENT_PREFIX"))
        recovery = method(CONTAINERS, "private boolean canDeleteRetiredContainer(")
        self.assertIn("!hasPersistedUser(account)", recovery)
        self.assertIn("NATIVE_RETIRED_PREFIX + id", recovery)
        self.assertGreaterEqual(CONTAINERS.count("canDeleteRetiredContainer("), 3, "startup/deferred deletion must retain the same durable native fence")

    def assert_registered(self, name, expected):
        registered = re.search(r'\{"' + name + r'",\s*"([^"]+)"', JNI)
        self.assertIsNotNone(registered, f"missing JNI registration {name}")
        self.assertEqual(registered.group(1), expected, f"Java/JNI ABI mismatch: {name}")


if __name__ == "__main__":
    unittest.main()
