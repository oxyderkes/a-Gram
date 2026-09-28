"""Run actual native Config/policy code with temporary files and injected I/O failures."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/TMessagesProj/jni/tgnet"


def without_includes(source):
    return re.sub(r"^\s*#include[^\n]*$", "", source, flags=re.MULTILINE)


def compile_fixture(source, target):
    if os.name == "nt":
        vswhere = Path(os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)")) / "Microsoft Visual Studio/Installer/vswhere.exe"
        installation = subprocess.check_output([str(vswhere), "-latest", "-products", "*", "-property", "installationPath"], text=True).strip()
        setup = Path(installation) / "VC/Auxiliary/Build/vcvars64.bat"
        if not setup.is_file():
            raise RuntimeError("MSVC C++ tools required for native regression fixture")
        executable = target / "native-config-test.exe"
        command = f'"{setup}" >nul && cl /nologo /std:c++17 /EHsc /W3 /D_CRT_SECURE_NO_WARNINGS "{source}" /Fe:"{executable}" /Fo:"{target / "fixture.obj"}"'
        # cmd.exe uses its own quote grammar, not the C argv quoting of list2cmdline.
        subprocess.run('cmd /d /s /c "' + command + '"', check=True, timeout=90)
    else:
        compiler = os.environ.get("CXX") or shutil.which("c++") or shutil.which("g++")
        if not compiler:
            raise RuntimeError("A C++17 host compiler is required for native Config regression tests")
        executable = target / "native-config-test"
        subprocess.run([compiler, "-std=c++17", "-O0", str(source), "-o", str(executable)], check=True, timeout=90)
    return executable


def main():
    fixture = (ROOT / ".github/tests/NativeConfigFixture.cpp.in").read_text(encoding="utf-8")
    for marker, filename in (("/* CONFIG_HEADER */", "Config.h"), ("/* CONFIG_SOURCE */", "Config.cpp"),
                             ("/* OWNER_POLICY */", "AgramNativeOwnerPolicy.h")):
        assert fixture.count(marker) == 1
        fixture = fixture.replace(marker, without_includes((NATIVE / filename).read_text(encoding="utf-8")))
    with tempfile.TemporaryDirectory(prefix="agram-native-config-") as directory:
        target = Path(directory)
        source = target / "fixture.cpp"
        source.write_text(fixture, encoding="utf-8")
        executable = compile_fixture(source, target)
        subprocess.run([str(executable), str(target / "data")], check=True, timeout=30)


if __name__ == "__main__":
    main()
