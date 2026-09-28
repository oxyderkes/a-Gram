"""Exercise production retention settings and ownership fences without Android or network."""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / ".github/scripts"))
from run_android_regressions import executable


def method(source, signature):
    assert source.count(signature) == 1, f"ambiguous extraction marker: {signature}"
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
    raise AssertionError(f"unterminated method: {signature}")


def main():
    sources = ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger"
    controller = (sources / "MessagesController.java").read_text(encoding="utf-8")
    config = (sources / "UserConfig.java").read_text(encoding="utf-8")
    containers = (sources / "AgramContainerManager.java").read_text(encoding="utf-8")
    fixture = (ROOT / ".github/tests/DeletedMessagesSettingsFixture.java.in").read_text(encoding="utf-8")
    methods = {
        "GETTER": method(controller, "public boolean isKeepDeletedMessagesEnabled()"),
        "SETTER": method(controller, "public boolean setKeepDeletedMessagesEnabled("),
        "CONFIG_SETTER": method(config, "boolean setKeepDeletedMessagesEnabled("),
        "CONTAINER_FENCE": method(containers, "public void runBoundSettingsUpdate("),
        "CONTAINER_GETTER": method(containers, "public boolean isKeepDeletedMessagesEnabled("),
        "CONTAINER_SETTER": method(containers, "void updateKeepDeletedMessages("),
    }
    parsed = method(containers, "private ContainerRecord fromJson(")
    start = parsed.index("record.keepDeletedMessages =")
    methods["READ"] = parsed[start:parsed.index(";", start) + 1]
    methods["WRITE"] = 'json.put("keep_deleted_messages", record.keepDeletedMessages);'
    assert methods["WRITE"] in method(containers, "private JSONObject toJson(")
    methods["COPY"] = "copy.keepDeletedMessages = source.keepDeletedMessages;"
    assert methods["COPY"] in method(containers, "private static ContainerRecord copyRecord(")
    methods["DEFAULT"] = "keepDeletedMessages = true;"
    assert "record." + methods["DEFAULT"] in method(containers, "private ContainerRecord createDefault(")
    assert '!json.has("keep_deleted_messages")' in method(containers, "private ContainerRecord readRecord(")
    batch_start = controller.index("LongSparseArray<ArrayList<Integer>> deletedMessagesFinal = deletedMessages;")
    batch_end = controller.index("if (deletedQuickReplyMessages != null)", batch_start)
    batch = controller[batch_start:batch_end]
    assert batch.count("isKeepDeletedMessagesEnabled()") == 1, "one setting snapshot must serve UI and storage"
    assert "final boolean keepDeletedMessages = deletedMessagesFinal != null && isKeepDeletedMessagesEnabled();" in batch
    assert batch.count("if (keepDeletedMessages)") == 2
    assert "deletedMessagesFinal != null && !keepDeletedMessages" in batch
    for name, body in methods.items():
        marker = f"/* PRODUCTION_{name} */"
        assert fixture.count(marker) == 1
        fixture = fixture.replace(marker, body)
    with tempfile.TemporaryDirectory(prefix="agram-retention-settings-") as directory:
        target = Path(directory)
        java = target / "DeletedMessagesSettingsFixture.java"
        java.write_text(fixture, encoding="utf-8")
        production = [sources / "AgramSessionLifecycle.java", sources / "AgramPreferenceTransaction.java"]
        stubs = [ROOT / ".github/tests/session_storage/android/content/SharedPreferences.java",
                 ROOT / ".github/tests/session_storage/org/telegram/messenger/FileLog.java"]
        subprocess.run([executable("javac"), "-encoding", "UTF-8", "--release", "8", "-d", str(target),
                        str(java), *map(str, production + stubs)], check=True, timeout=30)
        subprocess.run([executable("java"), "-ea", "-cp", str(target),
                        "org.telegram.messenger.DeletedMessagesSettingsFixture"], check=True, timeout=30)


if __name__ == "__main__":
    main()
