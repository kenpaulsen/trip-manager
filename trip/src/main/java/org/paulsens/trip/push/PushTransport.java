package org.paulsens.trip.push;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The HTTP seam under both gateways: one synchronous request in, one response out. Production wraps the
 * JDK client (HTTP/2, which APNs requires and every push service speaks); tests script responses.
 *
 * <p>Synchronous on purpose -- the sender runs on a virtual thread spawned by {@code TripThreads}, so
 * blocking on {@code HttpClient.send} costs nothing and keeps {@code CompletableFuture} out of the tree
 * ({@code ArchitectureTest}).
 */
public interface PushTransport {

    HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;

    /** The JDK client with the plan's budgets: 10 s to connect (requests carry their own 15 s timeout). */
    static PushTransport jdk() {
        final HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return request -> client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
