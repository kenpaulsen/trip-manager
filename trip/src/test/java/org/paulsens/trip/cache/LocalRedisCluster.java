package org.paulsens.trip.cache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testng.SkipException;

/**
 * A throwaway <b>3-shard</b> Redis/Valkey cluster, started and formed by the test itself.
 *
 * <p>The single-node helper in the webtest module cannot answer the question this exists for: whether a message
 * published on one shard reaches a subscriber attached to another. That is only observable with real slot ownership,
 * so this starts three servers with clustering enabled and forms a cluster across them.
 *
 * <p>Off by default and skipping rather than failing when it cannot run — the standard suite must stay runnable with
 * no daemons and no network. Enable with {@code -Dtrip.cluster.test=true}; both a server binary and a {@code cli}
 * binary are needed, because forming a cluster is a client-side operation.
 *
 * <p><b>Not a stand-in for ElastiCache Serverless.</b> It reproduces slot ownership and the cluster protocol, which
 * is what the delivery question turns on. It does not reproduce TLS, the managed endpoint, or — most importantly —
 * <em>invisible resharding</em>, which is the specific production event that makes sharded pub/sub unsafe. That
 * limitation is stated here rather than quietly omitted.
 */
final class LocalRedisCluster {

    static final String ENABLE_PROPERTY = "trip.cluster.test";
    static final int SHARDS = 3;
    /** Cluster slots are 0..16383, divided evenly across the shards by {@code --cluster create}. */
    static final int TOTAL_SLOTS = 16384;

    private static final String[] SERVER_BINARIES = {"valkey-server", "redis-server"};
    private static final String[] CLI_BINARIES = {"valkey-cli", "redis-cli"};
    private static final String READY_MARKER = "Ready to accept connections";

    private final List<Process> processes;
    private final List<Integer> ports;

    private LocalRedisCluster(final List<Process> processes, final List<Integer> ports) {
        this.processes = processes;
        this.ports = ports;
    }

    static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY));
    }

    static void requireEnabled() {
        if (!enabled()) {
            throw new SkipException("Cluster pub/sub test not enabled. Re-run with: mvn test -pl trip "
                    + "-Dtest=ValkeyClusterPubSubTest -D" + ENABLE_PROPERTY + "=true "
                    + "(needs redis-server/valkey-server and redis-cli/valkey-cli on the PATH)");
        }
    }

    /** The seed URI. One node is enough: the client discovers the rest from the topology. */
    String seedUri() {
        return "redis://127.0.0.1:" + ports.get(0);
    }

    List<Integer> ports() {
        return List.copyOf(ports);
    }

    /** Starts three nodes and forms a cluster, or skips with an actionable hint. */
    static LocalRedisCluster start() throws IOException, InterruptedException {
        final Path server = findOnPath(SERVER_BINARIES);
        final Path cli = findOnPath(CLI_BINARIES);
        if (server == null || cli == null) {
            throw new SkipException("-D" + ENABLE_PROPERTY + "=true was set, but a server and cli binary are both "
                    + "required (macOS: brew install valkey). Drop the flag to skip this test.");
        }
        final List<Process> procs = new ArrayList<>();
        final List<Integer> ports = new ArrayList<>();
        try {
            for (int i = 0; i < SHARDS; i++) {
                startNode(server, procs, ports);
            }
            formCluster(cli, ports);
            awaitClusterReady(cli, ports);
        } catch (final RuntimeException | IOException | InterruptedException ex) {
            procs.forEach(Process::destroyForcibly);
            throw ex;
        }
        return new LocalRedisCluster(procs, ports);
    }

    private static void startNode(
            final Path server, final List<Process> procs, final List<Integer> ports) throws IOException {
        final int port = freePort();
        final Path dir = Files.createTempDirectory("trip-cluster-" + port);
        final Process proc = new ProcessBuilder(server.toString(),
                        "--port", String.valueOf(port),
                        "--cluster-enabled", "yes",
                        // Per-node config file, inside this node's own directory: sharing one would have the nodes
                        // overwrite each other's identity and the cluster would never form.
                        "--cluster-config-file", "nodes.conf",
                        "--cluster-node-timeout", "2000",
                        "--save", "",
                        "--appendonly", "no",
                        "--dir", dir.toString())
                .redirectErrorStream(true)
                .start();
        awaitReady(proc, port);
        procs.add(proc);
        ports.add(port);
    }

    private static void formCluster(final Path cli, final List<Integer> ports) throws IOException,
            InterruptedException {
        final List<String> command = new ArrayList<>(List.of(
                cli.toString(), "--cluster", "create"));
        ports.forEach(port -> command.add("127.0.0.1:" + port));
        // No replicas: this test is about slot ownership, and replicas would only slow startup.
        command.addAll(List.of("--cluster-replicas", "0", "--cluster-yes"));
        final Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = readAll(proc);
        if (!proc.waitFor(30, TimeUnit.SECONDS) || proc.exitValue() != 0) {
            proc.destroyForcibly();
            throw new IllegalStateException("Unable to form a cluster across " + ports + ":\n" + output);
        }
    }

    /**
     * Waits until every node reports {@code cluster_state:ok}.
     *
     * <p>Forming a cluster returns before the nodes agree, and a client that connects during that window gets
     * CLUSTERDOWN or a partial topology — which would look exactly like the delivery bug under test.
     */
    private static void awaitClusterReady(final Path cli, final List<Integer> ports) throws IOException,
            InterruptedException {
        final long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (allNodesReady(cli, ports)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Cluster across " + ports + " never reached cluster_state:ok");
    }

    private static boolean allNodesReady(final Path cli, final List<Integer> ports) throws IOException,
            InterruptedException {
        for (final int port : ports) {
            final Process proc = new ProcessBuilder(
                            cli.toString(), "-p", String.valueOf(port), "cluster", "info")
                    .redirectErrorStream(true)
                    .start();
            final String info = readAll(proc);
            proc.waitFor(10, TimeUnit.SECONDS);
            if (!info.contains("cluster_state:ok")) {
                return false;
            }
        }
        return true;
    }

    void stop() {
        for (final Process proc : processes) {
            stopOne(proc);
        }
    }

    private static void stopOne(final Process proc) {
        if (proc == null || !proc.isAlive()) {
            return;
        }
        proc.destroy();
        try {
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        }
    }

    private static void awaitReady(final Process proc, final int port) throws IOException {
        final long deadline = System.currentTimeMillis() + 20_000;
        final StringBuilder log = new StringBuilder();
        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
            log.append(line).append('\n');
            if (line.contains(READY_MARKER)) {
                // Keep draining: a full pipe buffer would block the server mid-test.
                drainInBackground(reader);
                return;
            }
        }
        final String exit = proc.isAlive() ? "still running" : "exited with " + proc.exitValue();
        proc.destroyForcibly();
        throw new IllegalStateException("Node on port " + port + " never became ready (" + exit
                + "). Output:\n" + log);
    }

    private static void drainInBackground(final BufferedReader reader) {
        final Thread drain = new Thread(() -> discard(reader), "local-cluster-drain");
        drain.setDaemon(true);
        drain.start();
    }

    private static void discard(final BufferedReader reader) {
        try {
            while (reader.readLine() != null) {
                // discard
            }
        } catch (final IOException ignored) {
            // Server stopped; nothing to do.
        }
    }

    private static String readAll(final Process proc) throws IOException {
        final StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static Path findOnPath(final String[] names) {
        for (final String name : names) {
            final Path found = findOnPath(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Path findOnPath(final String name) {
        final String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (final String dir : path.split(java.io.File.pathSeparator)) {
            final Path candidate = Paths.get(dir, name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * A free port that a <em>cluster</em> node can actually use.
     *
     * <p>{@code new ServerSocket(0)} is not good enough here, and the reason is easy to lose an afternoon to: a
     * cluster node also opens a bus port at <b>port + 10000</b>, so the server refuses to start above 55535 with
     * "Redis port number too high". macOS hands out ephemeral ports from 49152 upward, so roughly half of them fail
     * — intermittently, which is worse than always.
     *
     * <p>So the port is drawn from a range that leaves room for the bus port, and both it and the bus port are
     * probed, since a free data port whose bus port is taken fails just as hard.
     */
    private static int freePort() throws IOException {
        final int lowest = 20_000;
        final int highest = 45_000;
        for (int attempt = 0; attempt < 100; attempt++) {
            final int candidate = lowest + (int) (Math.random() * (highest - lowest));
            if (isFree(candidate) && isFree(candidate + 10_000)) {
                return candidate;
            }
        }
        throw new IOException("No free port with a free cluster bus port (+10000) found in "
                + lowest + ".." + highest);
    }

    private static boolean isFree(final int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return socket.getLocalPort() == port;
        } catch (final IOException ex) {
            return false;
        }
    }
}
