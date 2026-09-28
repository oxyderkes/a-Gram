/* Agram, GPL v2 or later. Pure java.net transport guard, covered by JVM tests. */
package org.telegram.messenger;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.security.Permission;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A revoked handle cannot reopen its delegate. Already admitted socket
 * operations can race revocation until disconnect takes effect; this is not
 * an operating-system packet firewall.
 */
public final class AgramDirectHttpConnection extends HttpURLConnection {
    private final HttpURLConnection delegate;
    private final BooleanSupplier ownerAndRouteAllowed;
    private volatile boolean invalidated;

    public AgramDirectHttpConnection(HttpURLConnection delegate, BooleanSupplier ownerAndRouteAllowed) {
        super(delegate.getURL());
        if (ownerAndRouteAllowed == null) throw new IllegalArgumentException("Missing HTTP owner policy");
        this.delegate = delegate;
        this.ownerAndRouteAllowed = ownerAndRouteAllowed;
        delegate.setInstanceFollowRedirects(false);
    }

    public void checkValid() throws IOException {
        boolean allowed = false;
        if (!invalidated) {
            try { allowed = ownerAndRouteAllowed.getAsBoolean(); }
            catch (RuntimeException ignored) { }
        }
        if (!allowed || invalidated) {
            invalidate();
            throw new IOException("Container HTTP route or owner changed");
        }
    }

    /** Mark revoked without platform IO; safe while updating an owner registry. */
    public void revoke() {
        invalidated = true;
    }

    public void invalidate() {
        revoke();
        try { delegate.disconnect(); }
        catch (RuntimeException ignored) { }
    }

    @Override public void disconnect() { invalidate(); }
    @Override public boolean usingProxy() { return delegate.usingProxy(); }

    @Override public void connect() throws IOException {
        checkValid(); delegate.connect(); checkValid();
    }

    @Override public int getResponseCode() throws IOException {
        checkValid(); int result = delegate.getResponseCode(); checkValid(); return result;
    }

    @Override public String getResponseMessage() throws IOException {
        checkValid(); String result = delegate.getResponseMessage(); checkValid(); return result;
    }

    @Override public InputStream getInputStream() throws IOException {
        checkValid();
        InputStream stream = delegate.getInputStream();
        checkValid();
        return guardInput(stream);
    }

    @Override public OutputStream getOutputStream() throws IOException {
        checkValid();
        OutputStream stream = delegate.getOutputStream();
        checkValid();
        return new FilterOutputStream(stream) {
            @Override public void write(int value) throws IOException {
                checkValid(); out.write(value); checkValid();
            }
            @Override public void write(byte[] data, int offset, int length) throws IOException {
                checkValid(); out.write(data, offset, length); checkValid();
            }
            @Override public void flush() throws IOException {
                checkValid(); out.flush(); checkValid();
            }
            @Override public void close() throws IOException {
                // Output close can flush buffered bytes. After revocation only
                // disconnect the transport, never invoke an implicit flush.
                try { checkValid(); }
                catch (IOException ignored) { return; }
                out.close();
                checkValid();
            }
        };
    }

    private InputStream guardInput(InputStream stream) {
        if (stream == null) return null;
        return new FilterInputStream(stream) {
            @Override public int read() throws IOException {
                checkValid(); int result = in.read(); checkValid(); return result;
            }
            @Override public int read(byte[] data, int offset, int length) throws IOException {
                checkValid(); int result = in.read(data, offset, length); checkValid(); return result;
            }
            @Override public long skip(long amount) throws IOException {
                checkValid(); long result = in.skip(amount); checkValid(); return result;
            }
            @Override public int available() throws IOException {
                checkValid(); int result = in.available(); checkValid(); return result;
            }
            @Override public synchronized void reset() throws IOException {
                checkValid(); in.reset(); checkValid();
            }
            @Override public void close() throws IOException {
                // Some HTTP streams drain the response while closing for pool
                // reuse. A revoked stream must only disconnect, never drain.
                try { checkValid(); }
                catch (IOException ignored) { return; }
                in.close();
                checkValid();
            }
        };
    }

    private void checkHeaderAccess() {
        try { checkValid(); }
        catch (IOException error) {
            // URLConnection header accessors cannot declare checked exceptions.
            throw new IllegalStateException("Container HTTP route or owner changed", error);
        }
    }

    @Override public String getHeaderField(String name) {
        checkHeaderAccess(); String result = delegate.getHeaderField(name); checkHeaderAccess(); return result;
    }
    @Override public String getHeaderField(int index) {
        checkHeaderAccess(); String result = delegate.getHeaderField(index); checkHeaderAccess(); return result;
    }
    @Override public String getHeaderFieldKey(int index) {
        checkHeaderAccess(); String result = delegate.getHeaderFieldKey(index); checkHeaderAccess(); return result;
    }
    @Override public Map<String, List<String>> getHeaderFields() {
        checkHeaderAccess(); Map<String, List<String>> result = delegate.getHeaderFields(); checkHeaderAccess(); return result;
    }
    @Override public InputStream getErrorStream() {
        checkHeaderAccess(); InputStream result = delegate.getErrorStream(); checkHeaderAccess(); return guardInput(result);
    }
    @Override public Permission getPermission() throws IOException {
        checkValid(); return delegate.getPermission();
    }

    @Override public void setConnectTimeout(int value) { delegate.setConnectTimeout(value); }
    @Override public int getConnectTimeout() { return delegate.getConnectTimeout(); }
    @Override public void setReadTimeout(int value) { delegate.setReadTimeout(value); }
    @Override public int getReadTimeout() { return delegate.getReadTimeout(); }
    @Override public void setRequestProperty(String key, String value) { delegate.setRequestProperty(key, value); }
    @Override public void addRequestProperty(String key, String value) { delegate.addRequestProperty(key, value); }
    @Override public String getRequestProperty(String key) { return delegate.getRequestProperty(key); }
    @Override public Map<String, List<String>> getRequestProperties() { return delegate.getRequestProperties(); }
    @Override public void setRequestMethod(String method) throws ProtocolException { delegate.setRequestMethod(method); }
    @Override public String getRequestMethod() { return delegate.getRequestMethod(); }
    @Override public void setDoInput(boolean value) { delegate.setDoInput(value); }
    @Override public boolean getDoInput() { return delegate.getDoInput(); }
    @Override public void setDoOutput(boolean value) { delegate.setDoOutput(value); }
    @Override public boolean getDoOutput() { return delegate.getDoOutput(); }
    @Override public void setUseCaches(boolean value) { delegate.setUseCaches(value); }
    @Override public boolean getUseCaches() { return delegate.getUseCaches(); }
    @Override public void setDefaultUseCaches(boolean value) { delegate.setDefaultUseCaches(value); }
    @Override public boolean getDefaultUseCaches() { return delegate.getDefaultUseCaches(); }
    @Override public void setIfModifiedSince(long value) { delegate.setIfModifiedSince(value); }
    @Override public long getIfModifiedSince() { return delegate.getIfModifiedSince(); }
    @Override public void setAllowUserInteraction(boolean value) { delegate.setAllowUserInteraction(value); }
    @Override public boolean getAllowUserInteraction() { return delegate.getAllowUserInteraction(); }
    @Override public void setFixedLengthStreamingMode(int value) { delegate.setFixedLengthStreamingMode(value); }
    @Override public void setFixedLengthStreamingMode(long value) { delegate.setFixedLengthStreamingMode(value); }
    @Override public void setChunkedStreamingMode(int value) { delegate.setChunkedStreamingMode(value); }
    @Override public void setInstanceFollowRedirects(boolean value) {
        // Each redirect needs a new policy admission with the original owner.
        delegate.setInstanceFollowRedirects(false);
    }
    @Override public boolean getInstanceFollowRedirects() { return false; }
}
