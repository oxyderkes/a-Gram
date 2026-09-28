"""Compile the actual ImageLoader HTTP owner/redirect code with fake transport; no network."""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / ".github/scripts"))
from run_android_regressions import executable


def main():
    source = (ROOT / "android/TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java").read_text(encoding="utf-8")
    begin = "    private static final class HttpRedirectException"
    end = "    private class HttpFileTask "
    assert source.count(begin) == 1 and source.count(end) == 1, "production extraction markers changed"
    actual_owner = source[source.index(begin):source.index(end)]
    assert "AgramContainerManager.getInstance().getContainer(account)" in actual_owner
    fixture = (ROOT / ".github/tests/ImageHttpOwnerFixture.java.in").read_text(encoding="utf-8")
    assert fixture.count("/* PRODUCTION_OWNER */") == 1
    with tempfile.TemporaryDirectory(prefix="agram-image-http-") as directory:
        target = Path(directory)
        java = target / "ImageHttpOwnerFixture.java"
        java.write_text(fixture.replace("/* PRODUCTION_OWNER */", actual_owner), encoding="utf-8")
        subprocess.run([executable("javac"), "-encoding", "UTF-8", "--release", "8", "-d", str(target), str(java)],
                       check=True, timeout=30)
        subprocess.run([executable("java"), "-ea", "-cp", str(target), "ImageHttpOwnerFixture"],
                       check=True, timeout=30)


if __name__ == "__main__":
    main()
