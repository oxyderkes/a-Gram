"""Verify the merged Standalone manifest, not just overlay XML declarations."""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
A = "{http://schemas.android.com/apk/res/android}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    document = ET.parse(args.manifest).getroot()
    properties = (ROOT / "android/gradle.properties").read_text(encoding="utf-8")
    expected = re.search(r"^APP_VERSION_NAME\s*=\s*(.+)$", properties, re.MULTILINE).group(1).strip()
    assert document.get("package") == "app.manygram.messenger", "Wrong application identity"
    assert document.get(A + "versionName") == expected, "Stale/wrong merged manifest version"
    app = document.find("application")
    assert app is not None and app.get(A + "allowBackup") == "false", "Backup must remain disabled"
    names = {element.get(A + "name", "") for element in document.iter()}
    forbidden = {
        "org.telegram.messenger.GcmPushListenerService",
        "com.google.firebase.messaging.FirebaseMessagingService",
        "com.google.firebase.iid.FirebaseInstanceIdReceiver",
        "com.google.firebase.provider.FirebaseInitProvider",
        "com.google.firebase.MESSAGING_EVENT",
        "com.google.android.c2dm.intent.RECEIVE",
        "com.google.firebase.components:com.google.firebase.messaging.FirebaseMessagingRegistrar",
    }
    assert not names.intersection(forbidden), "Legacy global push survived manifest merging"
    assert "com.google.mlkit.common.internal.MlKitInitProvider" in names, "ML Kit initialization was accidentally removed"
    print("PASS merged Android manifest: identity, version, backup, no legacy push, ML Kit retained")


if __name__ == "__main__":
    main()
