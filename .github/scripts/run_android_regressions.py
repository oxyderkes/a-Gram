"""Compile and execute small production Java regressions; no Android SDK or secrets needed."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def executable(name):
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise SystemExit(f"JDK 17 required: {name} not found")
    return found


def main():
    sources = [
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramPushState.java",
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramThemeFileUtils.java",
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramGhostReadPolicy.java",
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramArchiveCopy.java",
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramNetworkPolicy.java",
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramDirectHttpConnection.java",
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramSessionLifecycle.java",
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramPreferenceTransaction.java",
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramAccountPreferenceKeys.java",
        ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/AgramNativeOwnerState.java",
    ]
    tests = [
        ROOT / ".github/tests/AgramPushStateTest.java",
        ROOT / ".github/tests/AgramPushLogoutTest.java",
        ROOT / ".github/tests/AgramThemeFileUtilsTest.java",
        ROOT / ".github/tests/AgramGhostReadPolicyTest.java",
        ROOT / ".github/tests/AgramArchiveCopyTest.java",
        ROOT / ".github/tests/AgramNetworkPolicyTest.java",
        ROOT / ".github/tests/AgramDirectHttpConnectionTest.java",
        ROOT / ".github/tests/AgramSessionLifecycleTest.java",
        ROOT / ".github/tests/AgramPreferenceTransactionTest.java",
        ROOT / ".github/tests/AgramAccountPreferenceKeysTest.java",
        ROOT / ".github/tests/AgramNativeOwnerStateTest.java",
        ROOT / ".github/tests/session_storage/android/content/SharedPreferences.java",
        ROOT / ".github/tests/session_storage/org/telegram/messenger/FileLog.java",
    ]
    test_classes = [
        "org.telegram.messenger.AgramPushStateTest",
        "org.telegram.messenger.AgramPushLogoutTest",
        "org.telegram.messenger.AgramThemeFileUtilsTest",
        "org.telegram.messenger.AgramGhostReadPolicyTest",
        "org.telegram.messenger.AgramArchiveCopyTest",
        "org.telegram.messenger.AgramNetworkPolicyTest",
        "org.telegram.messenger.AgramDirectHttpConnectionTest",
        "org.telegram.messenger.AgramSessionLifecycleTest",
        "org.telegram.messenger.AgramPreferenceTransactionTest",
        "org.telegram.messenger.AgramAccountPreferenceKeysTest",
        "org.telegram.messenger.AgramNativeOwnerStateTest",
    ]
    with tempfile.TemporaryDirectory(prefix="agram-regression-") as target:
        subprocess.run([executable("javac"), "-encoding", "UTF-8", "--release", "8", "-d", target,
                        *map(str, sources + tests)], check=True)
        for test_class in test_classes:
            subprocess.run([executable("java"), "-ea", "-cp", target, test_class],
                           check=True, timeout=30)
    subprocess.run([sys.executable, str(ROOT / ".github/tests/test_legacy_push_isolation.py")],
                   check=True, timeout=90)
    subprocess.run([sys.executable, str(ROOT / ".github/tests/test_dns_ownership.py")],
                   check=True, timeout=30)
    subprocess.run([sys.executable, str(ROOT / ".github/tests/test_image_http_owner.py")],
                   check=True, timeout=90)
    subprocess.run([sys.executable, str(ROOT / ".github/tests/test_deleted_messages_settings.py")],
                   check=True, timeout=90)
    subprocess.run([sys.executable, str(ROOT / ".github/tests/test_container_menu.py")],
                   check=True, timeout=30)
    subprocess.run([sys.executable, str(ROOT / ".github/tests/test_native_owner_contract.py")],
                   check=True, timeout=30)
    subprocess.run([sys.executable, str(ROOT / ".github/tests/test_native_config_io.py")],
                   check=True, timeout=150)


if __name__ == "__main__":
    main()
