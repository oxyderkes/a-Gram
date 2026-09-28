package org.telegram.messenger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgramArchiveCopyTest {
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Copy must fail without authorizing commit");
    }

    public static void main(String[] args) throws Exception {
        byte[] source = new byte[700_003];
        for (int i = 0; i < source.length; i++) source[i] = (byte) (i * 31);
        ByteArrayOutputStream staged = new ByteArrayOutputStream();
        assert AgramArchiveCopy.copy(new ByteArrayInputStream(source), staged,
                source.length, () -> true) == source.length;
        assert Arrays.equals(source, staged.toByteArray());
        fails(() -> AgramArchiveCopy.copy(new ByteArrayInputStream(source), new ByteArrayOutputStream(),
                source.length + 1L, () -> true));
        fails(() -> AgramArchiveCopy.copy(new ByteArrayInputStream(source), new ByteArrayOutputStream(),
                source.length - 1L, () -> true));
        fails(() -> AgramArchiveCopy.copy(new ByteArrayInputStream(source), new ByteArrayOutputStream(),
                source.length, () -> false));
        AtomicInteger ownershipChecks = new AtomicInteger();
        ByteArrayOutputStream interrupted = new ByteArrayOutputStream();
        fails(() -> AgramArchiveCopy.copy(new ByteArrayInputStream(source), interrupted,
                source.length, () -> ownershipChecks.incrementAndGet() <= 2));
        assert interrupted.size() > 0 && interrupted.size() < source.length;
        OutputStream diskFull = new OutputStream() {
            int remaining = 280_000;
            @Override public void write(int value) throws IOException {
                if (--remaining <= 0) throw new IOException("Injected disk full");
            }
        };
        fails(() -> AgramArchiveCopy.copy(new ByteArrayInputStream(source), diskFull,
                source.length, () -> true));
        // Retry starts from the intact source, not from a partial destination.
        ByteArrayOutputStream retry = new ByteArrayOutputStream();
        AgramArchiveCopy.copy(new ByteArrayInputStream(source), retry, source.length, () -> true);
        assert Arrays.equals(source, retry.toByteArray());
        System.out.println("PASS AgramArchiveCopy: exact bytes, truncated/growing source, owner change, disk full, retry");
    }
}
