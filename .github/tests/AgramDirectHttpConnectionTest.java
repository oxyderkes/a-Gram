package org.telegram.messenger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the production wrapper with a delegate that never contacts a network. */
public final class AgramDirectHttpConnectionTest {
    private interface CheckedCall { void run() throws Exception; }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void blocked(CheckedCall call) throws Exception {
        try {
            call.run();
            throw new AssertionError("Revoked operation reached its delegate");
        } catch (IOException | IllegalStateException expected) { }
    }

    private static final class FakeConnection extends HttpURLConnection {
        int operations, reads, writes, disconnects, outputCloses;
        Runnable afterResponse;
        CountDownLatch entered, release;
        final InputStream input = new InputStream() {
            @Override public int read() { reads++; return 7; }
            @Override public int read(byte[] data, int offset, int length) {
                reads++; if (length == 0) return 0; data[offset] = 7; return 1;
            }
            @Override public int available() { reads++; return 1; }
            @Override public void close() { reads++; }
        };
        final OutputStream output = new OutputStream() {
            @Override public void write(int value) { writes++; }
            @Override public void close() { outputCloses++; writes++; }
        };

        FakeConnection() throws Exception { super(new URL("https://example.invalid/resource")); }
        @Override public void connect() { operations++; }
        @Override public void disconnect() {
            disconnects++;
            if (release != null) release.countDown();
        }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() {
            operations++;
            if (afterResponse != null) afterResponse.run();
            return 200;
        }
        @Override public String getResponseMessage() { operations++; return "OK"; }
        @Override public InputStream getInputStream() throws IOException {
            operations++;
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                } catch (InterruptedException error) { throw new IOException(error); }
            }
            return input;
        }
        @Override public OutputStream getOutputStream() { operations++; return output; }
        @Override public InputStream getErrorStream() { operations++; return input; }
        @Override public String getHeaderField(String name) { operations++; return "1"; }
        @Override public String getHeaderField(int index) { operations++; return "1"; }
        @Override public String getHeaderFieldKey(int index) { operations++; return "Content-Length"; }
        @Override public Map<String, List<String>> getHeaderFields() {
            operations++; return Collections.singletonMap("Content-Length", Collections.singletonList("1"));
        }
    }

    public static void main(String[] args) throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        FakeConnection beforeConnect = new FakeConnection();
        AgramDirectHttpConnection revoked = new AgramDirectHttpConnection(beforeConnect, allowed::get);
        revoked.revoke();
        check(beforeConnect.disconnects == 0, "registry revocation must not block on platform IO");
        blocked(revoked::connect);
        blocked(revoked::getResponseCode);
        blocked(revoked::getResponseMessage);
        blocked(revoked::getInputStream);
        blocked(revoked::getOutputStream);
        blocked(revoked::getErrorStream);
        blocked(() -> revoked.getHeaderField("Location"));
        blocked(() -> revoked.getHeaderField(0));
        blocked(() -> revoked.getHeaderFieldKey(0));
        blocked(revoked::getHeaderFields);
        blocked(revoked::getContentLength);
        blocked(revoked::getDate);
        check(beforeConnect.operations == 0, "cancel-before-connect must not issue any request/header IO");

        FakeConnection live = new FakeConnection();
        AgramDirectHttpConnection guarded = new AgramDirectHttpConnection(live, allowed::get);
        guarded.setRequestProperty("Accept", "image/*");
        guarded.setConnectTimeout(1234);
        guarded.setReadTimeout(4321);
        guarded.setRequestMethod("POST");
        guarded.setDoOutput(true);
        guarded.setUseCaches(false);
        guarded.setInstanceFollowRedirects(true);
        check("image/*".equals(live.getRequestProperty("Accept")), "request properties forwarded");
        check(live.getConnectTimeout() == 1234 && live.getReadTimeout() == 4321, "timeouts forwarded");
        check("POST".equals(live.getRequestMethod()) && live.getDoOutput(), "method/body forwarded");
        check(!live.getUseCaches(), "cache policy forwarded");
        check(!live.getInstanceFollowRedirects() && !guarded.getInstanceFollowRedirects(), "no unguarded automatic redirect");
        guarded.connect();
        check(guarded.getResponseCode() == 200, "direct response works");
        InputStream input = guarded.getInputStream();
        OutputStream output = guarded.getOutputStream();
        check(input.read() == 7, "direct stream works");
        output.write(1);
        int readsBefore = live.reads, writesBefore = live.writes;
        allowed.set(false);
        blocked(input::read);
        blocked(() -> input.read(new byte[2]));
        blocked(input::available);
        blocked(() -> output.write(2));
        blocked(output::flush);
        input.close();
        output.close();
        check(live.reads == readsBefore && live.writes == writesBefore && live.outputCloses == 0,
                "revocation stops stream IO and implicit output-close flush");
        allowed.set(true);
        blocked(guarded::getResponseCode);
        check(live.disconnects > 0, "policy change disconnects underlying transport");

        // A policy failure after the delegate returns must reject its result.
        FakeConnection changedDuringResponse = new FakeConnection();
        changedDuringResponse.afterResponse = () -> allowed.set(false);
        AgramDirectHttpConnection response = new AgramDirectHttpConnection(changedDuringResponse, allowed::get);
        blocked(response::getResponseCode);
        check(changedDuringResponse.operations == 1 && changedDuringResponse.disconnects > 0,
                "late response cannot revive a revoked owner");

        FakeConnection unknownOwner = new FakeConnection();
        AgramDirectHttpConnection unknown = new AgramDirectHttpConnection(unknownOwner, () -> {
            throw new IllegalStateException("metadata unavailable");
        });
        blocked(unknown::connect);
        check(unknownOwner.operations == 0, "metadata errors fail closed");

        // Revocation can interrupt admitted IO without needing a registry lock.
        FakeConnection pending = new FakeConnection();
        pending.entered = new CountDownLatch(1);
        pending.release = new CountDownLatch(1);
        AgramDirectHttpConnection concurrent = new AgramDirectHttpConnection(pending, () -> true);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { concurrent.getInputStream(); result.set(new AssertionError("Revoked result accepted")); }
            catch (IOException expected) { }
            catch (Throwable error) { result.set(error); }
        });
        worker.start();
        check(pending.entered.await(5, TimeUnit.SECONDS), "delegate operation entered");
        concurrent.invalidate();
        worker.join(5000);
        check(!worker.isAlive() && result.get() == null, "disconnect interrupts admitted IO and rejects its result");
        blocked(concurrent::connect);
        check(pending.operations == 1, "later operation cannot reopen disconnected delegate");
        System.out.println("PASS AgramDirectHttpConnection: revocation, headers, streams, redirects, races, owner errors");
    }
}
