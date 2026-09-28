package org.telegram.messenger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

/** Bounded copy phase only. The caller owns fsync, atomic commit and pending-source pins. */
public final class AgramArchiveCopy {
    private AgramArchiveCopy() { }

    public static long copy(InputStream input, OutputStream output, long expectedLength,
                            BooleanSupplier stillOwned) throws IOException {
        if (expectedLength <= 0) throw new IOException("Invalid archive source length");
        byte[] buffer = new byte[256 * 1024];
        long copied = 0;
        while (true) {
            if (!stillOwned.getAsBoolean()) throw new IOException("Archive owner changed");
            int count = input.read(buffer);
            if (count < 0) break;
            if (count == 0) continue;
            if (count > expectedLength - copied) throw new IOException("Archive source grew");
            output.write(buffer, 0, count);
            copied += count;
        }
        if (copied != expectedLength) throw new IOException("Archive source is incomplete");
        if (!stillOwned.getAsBoolean()) throw new IOException("Archive owner changed");
        return copied;
    }
}
