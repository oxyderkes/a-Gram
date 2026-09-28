/* Agram, GPL v2 or later. No Android dependencies: covered by JVM regression tests. */
package org.telegram.messenger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Runtime observations, never a persisted claim that a socket survived process death. */
public final class AgramPushState {
    private static final Map<Integer, Binding> BINDINGS = new HashMap<>();

    public static final class Binding {
        private final int account;
        private final String containerId;
        private final String endpoint;
        private long streamRevision;
        private String stream = "stopped";
        private String registration = "unknown";
        private String streamError = "";
        private String registrationError = "";
        private long updatedAt;
        private long registrationAttemptAt;

        private Binding(int account, String containerId, String endpoint) {
            this.account = account;
            this.containerId = containerId;
            this.endpoint = endpoint;
        }
    }

    public static final class Snapshot {
        public final String stream, registration, streamError, registrationError;
        public final long updatedAt;

        private Snapshot(Binding binding) {
            stream = binding == null ? "stopped" : binding.stream;
            registration = binding == null ? "unknown" : binding.registration;
            streamError = binding == null ? "" : binding.streamError;
            registrationError = binding == null ? "" : binding.registrationError;
            updatedAt = binding == null ? 0 : binding.updatedAt;
        }
    }

    private AgramPushState() { }

    public static synchronized Binding bind(int account, String containerId, String endpoint) {
        if (account < 0 || account >= 32 || containerId == null || endpoint == null) {
            throw new IllegalArgumentException("Invalid push binding");
        }
        Binding binding = BINDINGS.get(account);
        if (binding == null || !Objects.equals(binding.containerId, containerId)
                || !Objects.equals(binding.endpoint, endpoint)) {
            binding = new Binding(account, containerId, endpoint);
            BINDINGS.put(account, binding);
        }
        return binding;
    }

    public static synchronized void clear(int account) {
        BINDINGS.remove(account);
    }

    /** Delayed logout must not erase a replacement container's runtime state. */
    public static synchronized void clear(int account, String containerId) {
        Binding binding = BINDINGS.get(account);
        if (binding != null && Objects.equals(binding.containerId, containerId)) {
            BINDINGS.remove(account);
        }
    }

    public static synchronized Snapshot snapshot(int account, String containerId) {
        Binding binding = BINDINGS.get(account);
        return new Snapshot(binding != null && Objects.equals(binding.containerId, containerId) ? binding : null);
    }

    public static synchronized long beginStream(Binding binding) {
        if (!current(binding)) return -1;
        binding.streamRevision++;
        binding.stream = "connecting";
        binding.streamError = "";
        binding.updatedAt = System.currentTimeMillis();
        return binding.streamRevision;
    }

    public static synchronized void stream(Binding binding, long revision, String state, String error) {
        if (!current(binding) || revision != binding.streamRevision) return;
        binding.stream = state;
        binding.streamError = error == null ? "" : error;
        binding.updatedAt = System.currentTimeMillis();
    }

    public static synchronized void stopStream(Binding binding, long revision) {
        if (!current(binding) || revision != binding.streamRevision) return;
        binding.streamRevision++;
        binding.stream = "stopped";
        binding.streamError = "";
        binding.updatedAt = System.currentTimeMillis();
    }

    public static synchronized boolean beginRegistration(Binding binding, long now) {
        if (!current(binding) || "registered".equals(binding.registration)
                || "registering".equals(binding.registration)) return false;
        if (binding.registrationAttemptAt > 0 && now >= binding.registrationAttemptAt
                && now - binding.registrationAttemptAt < 30_000L) return false;
        binding.registration = "registering";
        binding.registrationError = "";
        binding.registrationAttemptAt = now;
        binding.updatedAt = now;
        return true;
    }

    public static synchronized void registration(Binding binding, boolean success, String safeError) {
        if (!current(binding)) return;
        binding.registration = success ? "registered" : "error";
        binding.registrationError = success || safeError == null ? "" : safeError;
        binding.updatedAt = System.currentTimeMillis();
    }

    private static boolean current(Binding binding) {
        return binding != null && BINDINGS.get(binding.account) == binding;
    }
}
