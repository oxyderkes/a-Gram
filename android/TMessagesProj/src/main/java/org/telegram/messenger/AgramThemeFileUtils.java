package org.telegram.messenger;

/** Utilities for parsing Telegram theme files without changing their raw byte offsets. */
public final class AgramThemeFileUtils {

    private AgramThemeFileUtils() {
    }

    /**
     * Removes the carriage-return half of a CRLF terminator after the caller has already
     * consumed LF. Other whitespace and additional carriage returns are intentionally kept.
     */
    public static String normalizeLine(String line) {
        if (line != null && !line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }
}
