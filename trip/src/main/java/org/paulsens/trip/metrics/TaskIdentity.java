package org.paulsens.trip.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * Which server (ECS task) this is, for the metrics keys: the task's own id, so blue/green deploys, which run two
 * tasks at once, and a later task that reuses an IP address never mix their minutes.
 *
 * <p>Only the LAST segment of the task ARN is kept, never the ARN: the ARN carries the account id, and this repo
 * is public. Outside ECS (a laptop, the webtests) the id is {@code local-} plus the host name.
 */
@Slf4j
final class TaskIdentity {

    /** The environment variable ECS sets to the task metadata endpoint (version 4). */
    static final String METADATA_ENV = "ECS_CONTAINER_METADATA_URI_V4";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    /** Built once and shared, on virtual threads (threading.md: a per-call client leaks a selector thread). */
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    private TaskIdentity() {
    }

    /** This server's id, from the real environment and the real metadata endpoint. */
    static String resolve() {
        return resolve(System.getenv(), TaskIdentity::fetch);
    }

    /**
     * The id from {@code env} and a fetch of the metadata endpoint's {@code /task} document; the local fallback
     * whenever either is missing or unreadable.
     */
    static String resolve(final Map<String, String> env, final Function<URI, Optional<String>> fetch) {
        final String endpoint = env.get(METADATA_ENV);
        final Optional<String> arn = endpoint == null || endpoint.isBlank() ? Optional.empty()
                : fetch.apply(URI.create(endpoint.trim() + "/task")).flatMap(TaskIdentity::taskArn);
        return arn.map(TaskIdentity::lastSegment).map(TaskIdentity::safe).filter(id -> !id.isEmpty())
                .orElseGet(() -> "local-" + safe(env.getOrDefault("HOSTNAME", "host")));
    }

    static Optional<String> fetch(final URI uri) {
        try {
            final HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
            final HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (final IOException ex) {
            log.warn("Task metadata unreadable ({}); metrics use the local id", ex.getMessage());
            return Optional.empty();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Optional<String> taskArn(final String body) {
        try {
            return Optional.ofNullable(JSON.readTree(body).path("TaskARN").textValue());
        } catch (final IOException ex) {
            return Optional.empty();
        }
    }

    private static String lastSegment(final String arn) {
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    /** Letters, digits and hyphens only, at most 64: a key segment, never a hash tag or a separator. */
    static String safe(final String raw) {
        final String clean = raw.replaceAll("[^A-Za-z0-9-]", "");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }
}
