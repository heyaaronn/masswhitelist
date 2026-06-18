package com.example.masswhitelist;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Resolves Minecraft Java usernames to UUIDs using the public Mojang API.
 *
 * <p>Uses the bulk lookup endpoint (up to 10 names per request) for efficiency, and
 * falls back to single lookups if a bulk request fails. Respects HTTP 429 rate limits
 * with a simple backoff. All methods here are blocking and must be called OFF the main
 * server thread.</p>
 */
public final class MojangResolver {

    private static final String BULK_URL =
            "https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname";
    private static final String SINGLE_URL =
            "https://api.mojang.com/users/profiles/minecraft/";

    /** Valid Minecraft Java usernames: 3-16 characters of letters, digits and underscore. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private static final int CHUNK_SIZE = 10;
    private static final int MAX_ATTEMPTS = 5;

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final Logger logger;

    public MojangResolver(Logger logger) {
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static boolean isValidFormat(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Resolves a list of (already format-validated) names.
     *
     * @return an outcome containing the profiles that were found (keyed by lower-case name)
     *         and the set of names that could not be checked due to network/API errors.
     *         Names that are simply absent from {@code found} and not in {@code errored}
     *         are valid-looking names with no matching Minecraft account.
     */
    public ResolveOutcome resolve(List<String> names) {
        ResolveOutcome outcome = new ResolveOutcome();
        for (List<String> chunk : chunk(names, CHUNK_SIZE)) {
            try {
                for (Profile p : bulkLookup(chunk)) {
                    if (p != null && p.name != null && p.id != null) {
                        outcome.found.put(p.name.toLowerCase(Locale.ROOT), p);
                    }
                }
            } catch (Exception bulkError) {
                logger.warning("Bulk lookup failed (" + bulkError.getMessage()
                        + "); falling back to single lookups for this batch.");
                for (String name : chunk) {
                    try {
                        Profile p = singleLookup(name);
                        if (p != null) {
                            outcome.found.put(name.toLowerCase(Locale.ROOT), p);
                        }
                    } catch (Exception singleError) {
                        outcome.errored.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
            sleepQuietly(120); // gentle pacing to stay well under Mojang's rate limit
        }
        return outcome;
    }

    private List<Profile> bulkLookup(List<String> chunk) throws IOException, InterruptedException {
        String body = gson.toJson(chunk);
        for (int attempt = 0; ; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BULK_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "MassWhitelist/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 200) {
                Profile[] parsed = gson.fromJson(response.body(), Profile[].class);
                return parsed == null ? List.of() : Arrays.asList(parsed);
            }
            if (code == 429 && attempt < MAX_ATTEMPTS) {
                backoff(response, attempt);
                continue;
            }
            throw new IOException("HTTP " + code + " from bulk endpoint");
        }
    }

    /** @return the profile, or {@code null} if there is no such account (HTTP 204/404). */
    private Profile singleLookup(String name) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(SINGLE_URL + name))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "MassWhitelist/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 200) {
                Profile p = gson.fromJson(response.body(), Profile.class);
                return (p != null && p.id != null) ? p : null;
            }
            if (code == 204 || code == 404) {
                return null;
            }
            if (code == 429 && attempt < MAX_ATTEMPTS) {
                backoff(response, attempt);
                continue;
            }
            throw new IOException("HTTP " + code + " from single endpoint for " + name);
        }
    }

    private void backoff(HttpResponse<?> response, int attempt) throws InterruptedException {
        long ms = 1000L * (attempt + 1);
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                ms = Math.max(ms, Long.parseLong(retryAfter.get().trim()) * 1000L);
            } catch (NumberFormatException ignored) {
                // header not a plain number of seconds; keep computed backoff
            }
        }
        Thread.sleep(Math.min(ms, 10_000L));
    }

    private static List<List<String>> chunk(List<String> list, int size) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return chunks;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Result of resolving a batch of names. */
    public static final class ResolveOutcome {
        /** Found profiles, keyed by lower-case username. */
        public final Map<String, Profile> found = new HashMap<>();
        /** Lower-case names that could not be checked because of a network/API error. */
        public final Set<String> errored = new HashSet<>();
    }

    /** A Mojang profile as returned by the API (id is the 32-char dashless UUID). */
    public static final class Profile {
        public String id;
        public String name;

        public String canonicalName() {
            return name;
        }

        public UUID uuid() {
            String dashed = id.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5");
            return UUID.fromString(dashed);
        }
    }
}
