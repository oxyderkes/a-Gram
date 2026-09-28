"""Verify bundled themes, CRLF-safe parsing, and Agram settings theme integration."""

from argparse import ArgumentParser
from hashlib import sha256
from pathlib import Path
import re
import zipfile


ROOT = Path(__file__).resolve().parents[2]
THEME_HASHES = {
    "arctic.attheme": "37d6359d3610cae442e7805892802d2f743d1237872835bdbda7a568fdb0f205",
    "bluebubbles.attheme": "f9cca1a737d012003464770b1b1e1953f3e820722d0f4b4c33ff35d117695fa2",
    "darkblue.attheme": "99763f1e5094843a3076fcaf4a1cabd7466eeb3b4e715caae3336f67b254ea37",
    "day.attheme": "889713b6dfdc03494ed9cf27277fea904bc93193972f1b569a7907331898bcfc",
    "night.attheme": "90a966f90687aaba263d013d8a8395a95a2863799972f110a2597a4530c37628",
}

# On Windows, pinned upstream blobs must be exported with
# `git -c core.autocrlf=false archive ...`; otherwise Git can silently rewrite the theme header.


def fail(message):
    raise SystemExit(f"theme verification failed: {message}")


def digest(data):
    return sha256(data).hexdigest()


def resolve_path(value):
    path = Path(value)
    return path if path.is_absolute() else (Path.cwd() / path).resolve()


def verify_attributes(path):
    if not path.is_file():
        fail(f"missing attributes file: {path}")
    matching_rules = []
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split()
        if fields and fields[0] == "*.attheme":
            matching_rules.append(fields[1:])
    if not matching_rules or "-text" not in matching_rules[-1]:
        fail(f"last *.attheme rule must include -text: {path}")


def verify_theme_sources(source_root):
    assets = source_root / "TMessagesProj/src/main/assets"
    for name, expected in THEME_HASHES.items():
        path = assets / name
        if not path.is_file():
            fail(f"missing source asset: {path}")
        data = path.read_bytes()
        actual = digest(data)
        if actual != expected:
            fail(f"{path} SHA-256 {actual}, expected {expected}")
        verify_lf_theme_header(path, data)

    theme_java = source_root / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java"
    if not theme_java.is_file():
        fail(f"missing Theme.java: {theme_java}")
    source = theme_java.read_text(encoding="utf-8")
    method_start = source.find("public static SparseIntArray getThemeFileValues(")
    if method_start < 0:
        fail("Theme.getThemeFileValues was not found")
    method_end = source.find("\n    public static ", method_start + 1)
    method = source[method_start:method_end if method_end >= 0 else len(source)]

    assignment = re.search(
        r"String\s+line\s*=\s*AgramThemeFileUtils\.normalizeLine\s*\(\s*"
        r"new\s+String\s*\(\s*bytes\s*,\s*start\s*,\s*len\s*-\s*1\s*\)\s*\)\s*;",
        method,
    )
    if not assignment:
        fail("Theme.java must normalize the raw line immediately after excluding LF")

    checks = [
        ('line.startsWith("WLS=")', "WLS"),
        ('line.startsWith("WPS")', "WPS"),
        ("line.indexOf('=')", "theme key"),
    ]
    previous = assignment.start()
    for token, label in checks:
        position = method.find(token)
        if position < 0:
            fail(f"Theme.java {label} parsing check was not found")
        if position <= previous:
            fail(f"Theme.java must normalize a line before the {label} check")
        previous = position

    verify_settings_theme(source_root)
    verify_night_surfaces(assets / "night.attheme")


def verify_settings_theme(source_root):
    path = source_root / "TMessagesProj/src/main/java/org/telegram/ui/AgramContainerSetupActivity.java"
    if not path.is_file():
        fail(f"missing settings activity: {path}")
    source = path.read_text(encoding="utf-8")

    def method(name, signature=None):
        # Scope checks to one member without depending on its complete implementation.
        matches = re.finditer(
            rf"^    (?:public|private|protected) [^\n]*\b{re.escape(name)}\([^)]*\)\s*\{{.*?^    \}}",
            source, re.MULTILINE | re.DOTALL,
        )
        match = next((item for item in matches if signature is None
                      or signature in item.group().split("{", 1)[0]), None)
        if not match:
            fail(f"settings method {name} was not found")
        return match.group()

    def require(text, pattern, message):
        if not re.search(pattern, text, re.DOTALL):
            fail(f"settings {message}")

    require(method("updatePageBackground"),
            r"Theme\.isCurrentThemeDark\(\)\s*\?\s*Theme\.key_windowBackgroundWhite\s*:\s*Theme\.key_windowBackgroundGray",
            "must use the normal list surface in dark themes and gray in light themes")
    for name in ("createView", "onResume"):
        require(method(name), r"\bupdatePageBackground\(\)", f"{name} must refresh the page surface")
    require(method("createView"), r"themeDescriptions\.clear\(\)",
            "must discard descriptions for views from a previous creation")

    for name, target, key in (
        ("card", "view", "dialogBackground"),
        ("action", "view", "dialogBackgroundGray"),
        ("input", "view", "dialogBackgroundGray"),
        ("addProfileCard", "preview", "dialogBackgroundGray"),
    ):
        require(method(name), rf"themedBackground\(\s*{target}\s*,\s*Theme\.key_{key}\s*,",
                f"{name} must use the Telegram {key} surface")

    background = method("themedBackground")
    require(background, r"setBackground\(Theme\.createRoundRectDrawable\(",
            "backgrounds must retain rounded shapes")
    require(background,
            r"themeDescriptions\.add\(new ThemeDescription\(view,\s*ThemeDescription\.FLAG_BACKGROUNDFILTER,.*?\bcolorKey\)\)",
            "rounded backgrounds must register paint updates with their theme key")
    require(method("text"),
            r"themeDescriptions\.add\(new ThemeDescription\(view,\s*ThemeDescription\.FLAG_TEXTCOLOR,.*?\bcolorKey\)\)",
            "text helpers must register live text colors")

    input_source = method("input")
    require(input_source, r"new EditTextBoldCursor\(", "inputs must support themed cursor updates")
    for flag, key in (
        ("TEXTCOLOR", "windowBackgroundWhiteBlackText"),
        ("HINTTEXTCOLOR", "windowBackgroundWhiteHintText"),
        ("CURSORCOLOR", "windowBackgroundWhiteBlackText"),
    ):
        require(input_source,
                rf"themeDescriptions\.add\(new ThemeDescription\(view,\s*ThemeDescription\.FLAG_{flag},[^;]*Theme\.key_{key}\)\)",
                f"inputs must register live {flag.lower()} updates")

    for name, cell in (("radio", "RadioButtonCell"), ("settingSwitch", "TextCheckCell")):
        cell_source = method(name, "String subtitle" if name == "settingSwitch" else None)
        for field, key in (("textView", "windowBackgroundWhiteBlackText"),
                           ("valueTextView", "windowBackgroundWhiteGrayText2")):
            require(cell_source,
                    rf"themeDescriptions\.add\(new ThemeDescription\(view,[^;]*{cell}\.class[^;]*\"{field}\"[^;]*Theme\.key_{key}\)\)",
                    f"{name} must register live {field} colors")
        require(cell_source, r"themeDescriptions\.add\(new ThemeDescription\(view,\s*ThemeDescription\.FLAG_SELECTOR,",
                f"{name} must register live selectors")
    for flag, key in (("CHECKBOX", "radioBackground"), ("CHECKBOXCHECK", "radioBackgroundChecked")):
        require(method("radio"),
                rf"ThemeDescription\.FLAG_{flag},[^;]*\"radioButton\"[^;]*Theme\.key_{key}\)\)",
                f"radio controls must register live {key} colors")
    switch_source = method("settingSwitch", "String subtitle")
    require(method("settingSwitch", "String title, boolean checked"), r"return settingSwitch\(context, shortTitle,",
            "short-label switch helper must delegate to the themed cell helper")
    for key in ("switchTrack", "switchTrackChecked", "windowBackgroundWhite"):
        require(switch_source, rf"Theme\.key_{key}\b", f"switch controls must register {key}")
    require(switch_source, r"themeDescriptions\.add\(new ThemeDescription\(view,[^;]*\"checkBox\"[^;]*\bkey\)\)",
            "switch controls must register their live colors")

    descriptions = method("getThemeDescriptions")
    require(descriptions, r"new ArrayList<>\(themeDescriptions\)",
            "getThemeDescriptions must include registered view colors")
    delegate = re.search(r"ThemeDescriptionDelegate\s+(\w+)\s*=\s*new ThemeDescription\.ThemeDescriptionDelegate\(\)", descriptions)
    if not delegate:
        fail("settings getThemeDescriptions must register a surface update delegate")
    require(descriptions, r"void didSetColor\(\)\s*\{\s*updatePageBackground\(\)",
            "surface delegate must refresh the page on theme changes")
    require(descriptions, r"int pageStartColor\s*=[^;]*\(\(ColorDrawable\) fragmentView\.getBackground\(\)\)\.getColor\(\)",
            "theme animation must start from the actual page color")
    require(descriptions,
            r"onAnimationProgress\(float progress\).*?int target\s*=\s*Theme\.getNonAnimatedColor\(Theme\.isCurrentThemeDark\(\)\s*\?\s*Theme\.key_windowBackgroundWhite\s*:\s*Theme\.key_windowBackgroundGray\)",
            "theme animation must select the final light/dark page surface")
    require(descriptions, r"setBackgroundColor\(ColorUtils\.blendARGB\(pageStartColor,\s*target,\s*progress\)\)",
            "theme animation must blend actual page endpoints to avoid a black flash")
    for key in ("windowBackgroundWhite", "windowBackgroundGray"):
        require(descriptions, rf"descriptions\.add\(new ThemeDescription\(null,[^;]*\b{delegate[1]}\s*,\s*Theme\.key_{key}\)\)",
                f"getThemeDescriptions must observe {key} surface changes")
    require(descriptions, r"return descriptions;", "must return all live theme descriptions")


def verify_night_surfaces(path):
    # Only Night has these explicit pinned keys; other themes can use Theme.java fallbacks.
    colors = {}
    for line in path.read_bytes().split(b"\n"):
        if line.startswith(b"WPS"):
            break
        match = re.fullmatch(rb"(\w+)=(-?\d+)", line)
        if match:
            colors[match[1].decode("ascii")] = int(match[2]) & 0xFFFFFFFF

    def luminance(color):
        channels = [(color >> shift & 255) / 255 for shift in (16, 8, 0)]
        linear = [channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4
                  for channel in channels]
        return sum(channel * weight for channel, weight in zip(linear, (0.2126, 0.7152, 0.0722)))

    keys = ("windowBackgroundWhite", "dialogBackground", "dialogBackgroundGray")
    for key in (*keys, "windowBackgroundWhiteBlackText"):
        if key not in colors:
            fail(f"Night theme is missing required settings color {key}")
    text_luminance = luminance(colors["windowBackgroundWhiteBlackText"])
    for key in keys:
        color = colors[key]
        if color >> 24 != 255 or color & 0xFFFFFF == 0:
            fail(f"Night settings surface {key} must be opaque and nonblack")
        surface_luminance = luminance(color)
        contrast = (max(text_luminance, surface_luminance) + 0.05) / (min(text_luminance, surface_luminance) + 0.05)
        if contrast < 4.5:
            fail(f"Night primary text contrast on {key} is {contrast:.2f}:1, expected at least 4.5:1")


def verify_lf_theme_header(path, data):
    """Validate only textual bytes through WPS; bytes after it are an opaque wallpaper."""
    header_end = len(data)
    line_start = 0
    found_line = False
    for index, value in enumerate(data):
        if value != 0x0A:
            continue
        found_line = True
        if data[line_start:index].startswith(b"WPS"):
            header_end = index + 1
            break
        line_start = index + 1
    header = data[:header_end]
    if not found_line:
        fail(f"theme has no LF-delimited text header: {path}")
    if b"\r" in header:
        fail(f"theme text header contains CR/CRLF bytes (possible autocrlf rewrite): {path}")
    try:
        header.decode("utf-8")
    except UnicodeDecodeError as error:
        fail(f"theme text header is not UTF-8 at byte {error.start}: {path}")


def verify_apk(apk_path):
    if not apk_path.is_file():
        fail(f"APK not found: {apk_path}")
    if not zipfile.is_zipfile(apk_path):
        fail(f"not a ZIP/APK: {apk_path}")
    with zipfile.ZipFile(apk_path) as archive:
        names = archive.namelist()
        for name, expected in THEME_HASHES.items():
            entry = f"assets/{name}"
            if names.count(entry) != 1:
                fail(f"APK must contain exactly one {entry}, found {names.count(entry)}")
            actual = digest(archive.read(entry))
            if actual != expected:
                fail(f"APK {entry} SHA-256 {actual}, expected {expected}")


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        default=str(ROOT / "android"),
        help="Android source/overlay root containing TMessagesProj (default: repository android/)",
    )
    parser.add_argument("--apk", help="optional built APK whose packaged theme bytes must match")
    args = parser.parse_args()

    verify_attributes(ROOT / ".gitattributes")
    verify_attributes(ROOT / "android/.gitattributes")
    source_root = resolve_path(args.source)
    verify_theme_sources(source_root)
    if args.apk:
        verify_apk(resolve_path(args.apk))

    suffix = f" and APK {args.apk}" if args.apk else ""
    print(f"PASS Android themes: pinned sources, parser order, binary attributes, settings colors and live updates{suffix}")


if __name__ == "__main__":
    main()
