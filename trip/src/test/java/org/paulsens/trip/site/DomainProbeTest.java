package org.paulsens.trip.site;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * A custom domain is claimed only when it echoes a code only this server knows: the code is outstanding for
 * exactly one probe, a re-save of the stored domain is not probed, and every way a stranger's host can fail to
 * echo it is refused with words an admin can act on.
 */
public class DomainProbeTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private HttpServer server;

    @AfterMethod(alwaysRun = true)
    public void cleanUp() {
        DomainProbe.useCheck(null);
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    public void theCodeIsOutstandingOnlyWhileItsProbeRuns() {
        final List<String> seen = new ArrayList<>();
        DomainProbe.useCheck((domain, code) -> recordAndEcho(seen, domain, code));
        Assert.assertNull(DomainProbe.newClaimProblem("example.org", null));
        Assert.assertEquals(seen.get(0), "example.org");
        final String code = seen.get(1);
        Assert.assertNotNull(code, "the servlet answers the code while the probe is in flight");
        Assert.assertNull(DomainProbe.answer(code), "and never again after it");
        Assert.assertNull(DomainProbe.answer(null));
        Assert.assertNull(DomainProbe.answer("never-issued"));
    }

    @Test
    public void aRefusedProbeIsTheClaimsProblem() {
        DomainProbe.useCheck((domain, code) -> domain + " is not ours");
        Assert.assertEquals(DomainProbe.newClaimProblem("example.org", "other.org"), "example.org is not ours");
    }

    @Test
    public void reSavingTheStoredDomainIsNotANewClaim() {
        DomainProbe.useCheck((domain, code) -> "must not be asked");
        Assert.assertNull(DomainProbe.newClaimProblem("example.org", "https://www.Example.org/"));
        Assert.assertNull(DomainProbe.newClaimProblem(null, "example.org"), "a clear is never probed");
    }

    @Test
    public void localModeSkipsTheProbe() {
        final DomainProbe.Check previous = DomainProbe.useCheck(null);
        Assert.assertNull(previous);
        Assert.assertNull(DomainProbe.newClaimProblem("no-such-host.invalid", null),
                "the suite runs local: FakeData's domain and a laptop's saves need no certificate");
    }

    @Test
    public void anEchoOfTheCodeIsProof() throws IOException {
        final String origin = serve(exchange -> answer(exchange, 200, codeOf(exchange)));
        Assert.assertNull(DomainProbe.probe(HTTP, origin, "example.org", "abc123"));
    }

    @Test
    public void anyOtherAnswerIsRefused() throws IOException {
        final String wrong = serve(exchange -> answer(exchange, 200, "someone else's site"));
        Assert.assertTrue(DomainProbe.probe(HTTP, wrong, "example.org", "abc123").contains("not as this site"));
        server.stop(0);
        final String missing = serve(exchange -> answer(exchange, 404, "abc123"));
        Assert.assertTrue(DomainProbe.probe(HTTP, missing, "example.org", "abc123").contains("HTTP 404"));
    }

    @Test
    public void aHostWithoutAValidCertificateIsRefused() throws Exception {
        // A host that answers the TLS hello with plain HTTP: the handshake fails, as it does for a host whose
        // certificate this JVM does not trust.
        try (ServerSocket plain = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Thread answering = Thread.ofVirtual().start(() -> answerPlainly(plain));
            final String problem = DomainProbe.probe(HTTP, "https://127.0.0.1:" + plain.getLocalPort(),
                    "example.org", "abc");
            Assert.assertTrue(problem.contains("certificate"), problem);
            answering.join(Duration.ofSeconds(5));
        }
    }

    @Test
    public void aHostThatDoesNotAnswerIsRefused() throws IOException {
        final int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        final String problem = DomainProbe.probe(HTTP, "http://127.0.0.1:" + closed, "example.org", "abc");
        Assert.assertTrue(problem.contains("did not answer"), problem);
        final String unresolved = DomainProbe.probe(HTTP, "https://no-such-host.invalid", "example.org", "abc");
        Assert.assertTrue(unresolved.contains("does not resolve"), unresolved);
    }

    @Test
    public void aBrokenAnswerIsRefused() throws IOException {
        final String origin = serve(HttpExchange::close);
        final String problem = DomainProbe.probe(HTTP, origin, "example.org", "abc");
        Assert.assertTrue(problem.contains("could not be reached"), problem);
    }

    @Test
    public void anInterruptedProbeKeepsTheInterrupt() throws IOException {
        final String origin = serve(exchange -> answer(exchange, 200, codeOf(exchange)));
        Thread.currentThread().interrupt();
        try {
            Assert.assertTrue(DomainProbe.probe(HTTP, origin, "example.org", "abc").startsWith("Interrupted"));
            Assert.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /** A check that records what the servlet would answer mid-probe, and passes only on a real echo. */
    private static String recordAndEcho(final List<String> seen, final String domain, final String code) {
        seen.add(domain);
        seen.add(DomainProbe.answer(code));
        return code.equals(DomainProbe.answer(code)) ? null : "not echoed";
    }

    private static void answerPlainly(final ServerSocket plain) {
        try (Socket socket = plain.accept(); OutputStream out = socket.getOutputStream()) {
            // Wait for the client's hello, so the plain answer is what its handshake reads.
            socket.getInputStream().read();
            out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (final IOException ignored) {
            // The probe already gave up; nothing to answer.
        }
    }

    private String serve(final HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(DomainProbe.PATH, handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String codeOf(final HttpExchange exchange) {
        final String query = exchange.getRequestURI().getQuery();
        return query == null ? "" : query.replaceFirst("^code=", "");
    }

    private static void answer(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
