/*
 * This file is part of Agram and is licensed under GNU GPL v2 or later.
 */
package org.telegram.messenger;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Package-private bridge parsing and selection logic for {@link AgramTorManager}. */
final class AgramTorBridgePool {
    static final String MODE_OFF = "off";
    static final String MODE_AUTO = "auto";

    // These rendezvous values mirror the current Tor VPN Snowflake asset. They
    // are also supplied as IPtProxy defaults; values carried by an active
    // bridge line take precedence in AgramTorManager.
    static final String SNOWFLAKE_BROKER_URL = "https://1098762253.rsc.cdn77.org/";
    static final String SNOWFLAKE_FRONT_DOMAINS = "app.datapacket.com,www.datapacket.com";
    static final String SNOWFLAKE_ICE_SERVERS =
            "stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,"
                    + "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,"
                    + "stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,"
                    + "stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478";

    private static final String BUILT_IN_SNOWFLAKE_LINE =
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 "
                    + "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 "
                    + "url=" + SNOWFLAKE_BROKER_URL + " "
                    + "fronts=" + SNOWFLAKE_FRONT_DOMAINS + " "
                    + "ice=" + SNOWFLAKE_ICE_SERVERS + " "
                    + "utls-imitate=hellorandomizedalpn";
    private static final int MAX_BRIDGES = 64;
    private static final int MAX_SCORE_ENTRIES = MAX_BRIDGES + 1;
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "^(?:\\[[0-9a-fA-F:.]+]|[^\\s:]+):([0-9]{1,5})$");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENDPOINT_PATTERN = Pattern.compile(
            "(?i)(?:\\[[0-9a-f:]+]|(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[a-z0-9.-]+):[0-9]{1,5}");
    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("(?i)\\b[0-9a-f]{40}\\b");
    private static final Pattern SENSITIVE_ARG_PATTERN = Pattern.compile(
            "(?i)\\b(?:cert|url|front|fronts|ice|ampcache|sqsqueue|sqscreds)=[^\\s]+" );

    static final class Config {
        final boolean enabled;
        final String lines;
        final String mode;
        final boolean snowflakeFallback;
        final boolean readable;
        final String readError;
        String lastGood;
        final LinkedHashMap<String, Score> scores;

        Config(boolean enabled, String lines, String mode, boolean snowflakeFallback,
                boolean readable, String readError, String lastGood,
                LinkedHashMap<String, Score> scores) {
            this.enabled = enabled;
            this.lines = lines == null ? "" : lines;
            this.mode = enabled && MODE_AUTO.equals(mode) ? mode
                    : (enabled ? MODE_AUTO : MODE_OFF);
            this.snowflakeFallback = snowflakeFallback;
            this.readable = readable;
            this.readError = readError == null ? "" : readError;
            this.lastGood = lastGood == null ? "" : lastGood;
            this.scores = scores == null ? new LinkedHashMap<>() : scores;
        }

        static Config disabled() {
            return new Config(false, "", MODE_OFF, true, true, "", "", new LinkedHashMap<>());
        }

        static Config unreadable(Throwable error) {
            String message = error == null ? "unknown" : error.getClass().getSimpleName();
            return new Config(true, "", MODE_AUTO, true, false, message, "", new LinkedHashMap<>());
        }
    }

    static final class Candidate {
        final String id;
        final String transport;
        final List<String> bridgeLines;
        final boolean builtInSnowflake;
        final int sourceOrder;

        Candidate(String id, String transport, List<String> bridgeLines,
                boolean builtInSnowflake, int sourceOrder) {
            this.id = id;
            this.transport = transport;
            this.bridgeLines = Collections.unmodifiableList(new ArrayList<>(bridgeLines));
            this.builtInSnowflake = builtInSnowflake;
            this.sourceOrder = sourceOrder;
        }
    }

    private static final class Score {
        int successes;
        int failures;
        int consecutiveFailures;
        long lastSuccessAt;
        long lastFailureAt;

        static Score fromJson(JSONObject json) {
            Score result = new Score();
            if (json != null) {
                result.successes = boundedCount(json.optInt("ok", 0));
                result.failures = boundedCount(json.optInt("fail", 0));
                result.consecutiveFailures = boundedCount(json.optInt("streak", 0));
                result.lastSuccessAt = Math.max(0L, json.optLong("last_ok", 0L));
                result.lastFailureAt = Math.max(0L, json.optLong("last_fail", 0L));
            }
            return result;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("ok", successes);
            json.put("fail", failures);
            json.put("streak", consecutiveFailures);
            json.put("last_ok", lastSuccessAt);
            json.put("last_fail", lastFailureAt);
            return json;
        }

        private static int boundedCount(int value) {
            return Math.max(0, Math.min(10_000, value));
        }
    }

    private AgramTorBridgePool() {
    }

    /** Current public Tor Browser-compatible fallback; never include its value in diagnostics. */
    static String builtInSnowflakeLine() {
        return BUILT_IN_SNOWFLAKE_LINE;
    }

    static Config parse(JSONObject json) {
        if (json == null) {
            return Config.disabled();
        }
        boolean enabled = json.optBoolean("enabled", false);
        String mode = json.optString("mode", enabled ? MODE_AUTO : MODE_OFF);
        if (!MODE_AUTO.equals(mode) && !MODE_OFF.equals(mode)) {
            mode = enabled ? MODE_AUTO : MODE_OFF;
        }
        boolean fallback = !json.has("snowflake_fallback")
                || json.optBoolean("snowflake_fallback", true);
        LinkedHashMap<String, Score> scores = new LinkedHashMap<>();
        JSONObject scoreJson = json.optJSONObject("scores");
        if (scoreJson != null) {
            Iterator<String> keys = scoreJson.keys();
            while (keys.hasNext() && scores.size() < MAX_SCORE_ENTRIES) {
                String key = keys.next();
                if (isCandidateId(key)) {
                    scores.put(key, Score.fromJson(scoreJson.optJSONObject(key)));
                }
            }
        }
        Config result = new Config(enabled, normalize(json.optString("lines", "")), mode, fallback,
                true, "", json.optString("last_good", ""), scores);
        pruneScores(result);
        return result;
    }

    static Config updated(Config previous, boolean enabled, String normalizedLines) {
        Config result = new Config(enabled, normalizedLines, enabled ? MODE_AUTO : MODE_OFF,
                true, true, "", previous == null ? "" : previous.lastGood,
                previous == null ? new LinkedHashMap<>() : new LinkedHashMap<>(previous.scores));
        pruneScores(result);
        return result;
    }

    static JSONObject toJson(Config config) throws Exception {
        JSONObject json = new JSONObject();
        // Keep the original two fields for readers from older Agram builds.
        json.put("enabled", config.enabled);
        json.put("lines", config.lines);
        json.put("schema", 2);
        json.put("mode", config.enabled ? MODE_AUTO : MODE_OFF);
        json.put("snowflake_fallback", config.snowflakeFallback);
        if (!config.lastGood.isEmpty()) {
            json.put("last_good", config.lastGood);
        }
        JSONObject scores = new JSONObject();
        int count = 0;
        for (Map.Entry<String, Score> entry : config.scores.entrySet()) {
            if (count++ >= MAX_SCORE_ENTRIES) {
                break;
            }
            scores.put(entry.getKey(), entry.getValue().toJson());
        }
        json.put("scores", scores);
        return json;
    }

    static List<Candidate> buildPlan(Config config, long nowWallClock) {
        if (config == null || !config.enabled || !config.readable) {
            return Collections.emptyList();
        }
        ArrayList<Candidate> userCandidates = new ArrayList<>();
        LinkedHashSet<String> candidateIds = new LinkedHashSet<>();
        int sourceOrder = 0;
        for (String line : splitLines(config.lines)) {
            String id = candidateId(line);
            if (candidateIds.add(id)) {
                userCandidates.add(new Candidate(id, transportOf(line),
                        Collections.singletonList(line), false, sourceOrder++));
            }
        }
        Comparator<Candidate> comparator = (left, right) -> {
            long rightScore = rankingScore(config, right, nowWallClock);
            long leftScore = rankingScore(config, left, nowWallClock);
            int scoreOrder = Long.compare(rightScore, leftScore);
            return scoreOrder != 0 ? scoreOrder : Integer.compare(left.sourceOrder, right.sourceOrder);
        };
        Collections.sort(userCandidates, comparator);
        String builtInId = candidateId(BUILT_IN_SNOWFLAKE_LINE);
        if (config.snowflakeFallback && candidateIds.add(builtInId)) {
            // The bundled line is a fallback, not a competitor to a supplied
            // bridge. Exact current rdsys lines dedupe by stable candidate ID.
            userCandidates.add(new Candidate(builtInId, "snowflake",
                    Collections.singletonList(BUILT_IN_SNOWFLAKE_LINE), true, sourceOrder));
        }
        return userCandidates;
    }

    static void recordOutcome(Config config, Candidate candidate, boolean success, long nowWallClock) {
        if (config == null || candidate == null || !config.enabled || !config.readable) {
            return;
        }
        Set<String> knownIds = candidateIds(config);
        if (!knownIds.contains(candidate.id)) {
            return;
        }
        Score score = config.scores.get(candidate.id);
        if (score == null) {
            score = new Score();
            config.scores.put(candidate.id, score);
        }
        if (success) {
            score.successes = Math.min(10_000, score.successes + 1);
            score.consecutiveFailures = 0;
            score.lastSuccessAt = Math.max(0L, nowWallClock);
            config.lastGood = candidate.id;
        } else {
            score.failures = Math.min(10_000, score.failures + 1);
            score.consecutiveFailures = Math.min(10_000, score.consecutiveFailures + 1);
            score.lastFailureAt = Math.max(0L, nowWallClock);
            if (candidate.id.equals(config.lastGood)) {
                config.lastGood = "";
            }
        }
        pruneScores(config);
    }

    static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : value.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            line = line.replaceFirst("(?i)^Bridge\\s+", "").trim();
            rejectControls(line);
            if (line.length() > 1024) {
                throw new IllegalArgumentException("Некорректная строка моста");
            }
            line = line.replaceAll("[\\t\\x0B\\f ]+", " ");
            String[] tokens = line.split(" ");
            String transport = tokens[0].toLowerCase(Locale.US);
            boolean pluggable = "obfs4".equals(transport)
                    || "webtunnel".equals(transport)
                    || "snowflake".equals(transport);
            int addressIndex = pluggable ? 1 : 0;
            if (tokens.length <= addressIndex || !isValidAddress(tokens[addressIndex])) {
                throw new IllegalArgumentException(
                        "Поддерживаются обычные, obfs4, webtunnel и snowflake мосты");
            }
            if (pluggable) {
                line = transport + line.substring(tokens[0].length());
            }
            unique.add(line);
            if (unique.size() > MAX_BRIDGES) {
                throw new IllegalArgumentException("Слишком много строк Bridge (максимум 64)");
            }
        }
        StringBuilder result = new StringBuilder();
        for (String line : unique) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }

    static String sanitizeDiagnostic(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String result = value.replaceAll("(?i)Bridge\\s+[^\\r\\n]+", "Bridge [redacted]");
        result = URL_PATTERN.matcher(result).replaceAll("[url]");
        result = SENSITIVE_ARG_PATTERN.matcher(result).replaceAll("[bridge-arg]");
        result = FINGERPRINT_PATTERN.matcher(result).replaceAll("[fingerprint]");
        result = ENDPOINT_PATTERN.matcher(result).replaceAll("[endpoint]");
        result = result.replace('\r', ' ').replace('\n', ' ').trim();
        return result.length() > 320 ? result.substring(0, 320) + "…" : result;
    }

    static String transportOf(String line) {
        if (line == null) {
            return "vanilla";
        }
        int space = line.indexOf(' ');
        String first = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.US);
        if ("obfs4".equals(first) || "webtunnel".equals(first) || "snowflake".equals(first)) {
            return first;
        }
        return "vanilla";
    }

    static String candidateArgument(Candidate candidate, String... names) {
        if (candidate == null || names == null) {
            return "";
        }
        for (String line : candidate.bridgeLines) {
            for (String token : line.split(" ")) {
                int separator = token.indexOf('=');
                if (separator <= 0 || separator == token.length() - 1) {
                    continue;
                }
                String key = token.substring(0, separator);
                for (String name : names) {
                    if (name != null && key.equalsIgnoreCase(name)) {
                        return token.substring(separator + 1);
                    }
                }
            }
        }
        return "";
    }

    private static long rankingScore(Config config, Candidate candidate, long nowWallClock) {
        Score score = config.scores.get(candidate.id);
        long result = candidate.id.equals(config.lastGood) ? 10_000L : 0L;
        if (score == null) {
            return result;
        }
        result += Math.min(100, score.successes) * 80L;
        result -= Math.min(100, score.failures) * 35L;
        result -= Math.min(20, score.consecutiveFailures) * 180L;
        if (score.lastSuccessAt > 0 && nowWallClock - score.lastSuccessAt < 7L * 24 * 60 * 60 * 1000) {
            result += 250L;
        }
        if (score.lastFailureAt > score.lastSuccessAt) {
            long age = Math.max(0L, nowWallClock - score.lastFailureAt);
            if (age < 10L * 60 * 1000) {
                result -= 2_000L;
            } else if (age < 60L * 60 * 1000) {
                result -= 500L;
            }
        }
        return result;
    }

    private static void pruneScores(Config config) {
        Set<String> knownIds = candidateIds(config);
        config.scores.keySet().retainAll(knownIds);
        if (!knownIds.contains(config.lastGood)) {
            config.lastGood = "";
        }
    }

    private static Set<String> candidateIds(Config config) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String line : splitLines(config.lines)) {
            ids.add(candidateId(line));
        }
        if (config.enabled && config.snowflakeFallback) {
            ids.add(candidateId(BUILT_IN_SNOWFLAKE_LINE));
        }
        return ids;
    }

    private static List<String> splitLines(String value) {
        ArrayList<String> result = new ArrayList<>();
        if (value != null && !value.isEmpty()) {
            Collections.addAll(result, value.split("\\n"));
        }
        return result;
    }

    private static boolean isValidAddress(String address) {
        Matcher matcher = ADDRESS_PATTERN.matcher(address);
        if (!matcher.matches()) {
            return false;
        }
        try {
            int port = Integer.parseInt(matcher.group(1));
            return port > 0 && port <= 65535;
        } catch (NumberFormatException ignore) {
            return false;
        }
    }

    private static void rejectControls(String line) {
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (value < 0x20 && value != '\t') {
                throw new IllegalArgumentException("Некорректная строка моста");
            }
        }
    }

    private static String candidateId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                result.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
            }
            return result.toString();
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static boolean isCandidateId(String value) {
        return value != null && value.matches("[0-9a-f]{16}");
    }
}
