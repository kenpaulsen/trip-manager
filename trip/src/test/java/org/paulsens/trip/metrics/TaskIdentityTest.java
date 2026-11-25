package org.paulsens.trip.metrics;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/** Which server this is: the task id from the ECS metadata endpoint, never the whole ARN, else a local id. */
public class TaskIdentityTest {

    private static final String ARN = "arn:aws:ecs:us-west-2:123456789012:task/trip-cluster/0a1b2c3d4e5f";

    @Test
    public void onEcsTheIdIsTheLastSegmentOfTheTaskArn() throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v4/task", exchange -> reply(exchange, "{\"TaskARN\":\"" + ARN + "\"}"));
        server.start();
        try {
            final String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v4";
            final String id = TaskIdentity.resolve(Map.of(TaskIdentity.METADATA_ENV, endpoint), TaskIdentity::fetch);
            assertEquals(id, "0a1b2c3d4e5f", "the account id in the ARN never reaches a key");
            assertEquals(TaskIdentity.fetch(URI.create(endpoint + "/missing")), Optional.empty(), "a 404 is no id");
        } finally {
            server.stop(0);
        }
        assertEquals(TaskIdentity.fetch(URI.create("http://127.0.0.1:1/task")), Optional.empty(), "unreachable");
    }

    @Test
    public void anInterruptedFetchIsNoIdAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertEquals(TaskIdentity.fetch(URI.create("http://127.0.0.1:1/task")), Optional.empty());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept for the caller");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void elsewhereItIsLocalPlusTheHostName() {
        assertEquals(TaskIdentity.resolve(Map.of("HOSTNAME", "laptop.example"), uri -> Optional.empty()),
                "local-laptopexample");
        assertEquals(TaskIdentity.resolve(Map.of(TaskIdentity.METADATA_ENV, "http://x", "HOSTNAME", "h"),
                uri -> Optional.of("not json")), "local-h", "an unreadable document");
        assertEquals(TaskIdentity.resolve(Map.of(TaskIdentity.METADATA_ENV, " "), uri -> Optional.empty()),
                "local-host");
        assertEquals(TaskIdentity.safe("a{b}:c/" + "x".repeat(80)).length(), 64);
        assertTrue(TaskIdentity.resolve().length() > 0, "the real environment answers something");
    }

    private static void reply(final HttpExchange exchange, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
