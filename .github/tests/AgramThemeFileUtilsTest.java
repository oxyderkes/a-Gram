package org.telegram.messenger;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** JVM-only regression coverage for the byte-preserving theme line helper. */
public final class AgramThemeFileUtilsTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void checkEquals(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + printable(expected)
                    + ", actual=" + printable(actual));
        }
    }

    private static String printable(String value) {
        if (value == null) return "<null>";
        return '"' + value.replace("\r", "\\r").replace("\n", "\\n") + '"';
    }

    public static void main(String[] args) {
        checkEquals(null, AgramThemeFileUtils.normalizeLine(null), "null remains null");
        checkEquals("", AgramThemeFileUtils.normalizeLine(""), "empty remains empty");

        // Theme.java consumes LF before calling normalizeLine. A plain LF line therefore has
        // no terminator here, while a CRLF line still has exactly one terminal CR to remove.
        checkEquals("windowBackgroundWhite=1",
                AgramThemeFileUtils.normalizeLine("windowBackgroundWhite=1"), "LF line");
        checkEquals("windowBackgroundWhite=1",
                AgramThemeFileUtils.normalizeLine("windowBackgroundWhite=1\r"), "CRLF line");
        checkEquals("windowBackgroundWhite=1\n",
                AgramThemeFileUtils.normalizeLine("windowBackgroundWhite=1\n"),
                "helper must not strip LF itself");

        checkEquals("chat_inBubble=-16711936",
                AgramThemeFileUtils.normalizeLine("chat_inBubble=-16711936\r"),
                "negative numeric value");
        check(Integer.parseInt(AgramThemeFileUtils.normalizeLine("-1\r")) == -1,
                "normalized signed integer remains parseable");
        String negativeColor = AgramThemeFileUtils.normalizeLine("-16711936\r");
        check(Integer.parseInt(negativeColor) == -16711936,
                "known negative theme color remains parseable");
        checkEquals("chat_outBubble=#80ff00aa",
                AgramThemeFileUtils.normalizeLine("chat_outBubble=#80ff00aa\r"),
                "hex value");
        String hexColor = AgramThemeFileUtils.normalizeLine("#80ff00aa\r");
        check(Long.parseLong(hexColor.substring(1), 16) == 0x80ff00aaL,
                "normalized ARGB hex remains parseable");
        checkEquals("  chat_messageTextIn=#ffffff",
                AgramThemeFileUtils.normalizeLine("  chat_messageTextIn=#ffffff\r"),
                "leading spaces are significant");
        checkEquals("key=left\rright\r",
                AgramThemeFileUtils.normalizeLine("key=left\rright\r\r"),
                "only one terminal CR is removed; internal and extra CR remain");

        String wls = AgramThemeFileUtils.normalizeLine("WLS=https://example.invalid/wallpaper\r");
        check(wls.startsWith("WLS="), "WLS marker survives CRLF normalization");
        checkEquals("https://example.invalid/wallpaper", wls.substring(4), "WLS payload");
        check(AgramThemeFileUtils.normalizeLine("WPS\r").startsWith("WPS"),
                "WPS marker survives CRLF normalization");

        testRawOffsetsAndBinaryPayload();
        System.out.println("PASS AgramThemeFileUtils: CRLF normalization and raw WPS offsets");
    }

    /**
     * Models Theme.java's raw byte offset accounting only. This is deliberately not an Android
     * parser/rendering test: the bytes after WPS are opaque and must never be newline-normalized.
     */
    private static void testRawOffsetsAndBinaryPayload() {
        byte[] header = ("windowBackgroundWhite=#ffffff\r\n"
                + "chat_inBubble=-42\n"
                + "WLS=https://example.invalid/theme\r\n"
                + "WPS\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] payload = new byte[]{0x00, 0x0a, 0x0d, 0x7f, (byte) 0x80, (byte) 0xff, 0x41};
        byte[] file = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, file, header.length, payload.length);

        ModelResult result = modelHeader(file);
        check(result.wallpaperOffset == header.length,
                "WPS offset must be measured in original bytes, including CRLF");
        checkEquals("https://example.invalid/theme", result.wallpaperLink, "modeled WLS");
        checkEquals("#ffffff", result.values.get("windowBackgroundWhite"), "modeled hex");
        checkEquals("-42", result.values.get("chat_inBubble"), "modeled negative number");
        check(Arrays.equals(payload, Arrays.copyOfRange(file, result.wallpaperOffset, file.length)),
                "binary wallpaper payload remains byte-for-byte intact");
    }

    private static ModelResult modelHeader(byte[] file) {
        ModelResult result = new ModelResult();
        int start = 0;
        int rawPosition = 0;
        for (int index = 0; index < file.length; index++) {
            if (file[index] != '\n') continue;
            int rawLength = index - start + 1;
            String line = AgramThemeFileUtils.normalizeLine(
                    new String(file, start, rawLength - 1, StandardCharsets.ISO_8859_1));
            if (line.startsWith("WLS=")) {
                result.wallpaperLink = line.substring(4);
            } else if (line.startsWith("WPS")) {
                result.wallpaperOffset = rawPosition + rawLength;
                return result;
            } else {
                int equals = line.indexOf('=');
                if (equals >= 0) {
                    result.values.put(line.substring(0, equals), line.substring(equals + 1));
                }
            }
            start += rawLength;
            rawPosition += rawLength;
        }
        throw new AssertionError("WPS marker not found in modeled theme header");
    }

    private static final class ModelResult {
        final Map<String, String> values = new LinkedHashMap<>();
        String wallpaperLink;
        int wallpaperOffset = -1;
    }
}
