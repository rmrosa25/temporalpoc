package com.example.provisioning.kafka;

import kafka.server.KafkaConfig;
import kafka.server.KafkaRaftServer;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

/**
 * In-process Kafka broker using KRaft mode (no Zookeeper, no Docker).
 *
 * Starts a single-node Kafka cluster on a random available port.
 * Used when Docker is not available (e.g. Gitpod / CI environments
 * without CAP_NET_ADMIN).
 *
 * Lifecycle:
 *   EmbeddedKafkaBroker broker = EmbeddedKafkaBroker.start();
 *   String bootstrap = broker.bootstrapServers(); // "localhost:PORT"
 *   // ... run tests ...
 *   broker.close();
 *
 * The broker writes its data to a temp directory that is deleted on close().
 */
public class EmbeddedKafkaBroker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedKafkaBroker.class);

    private final KafkaRaftServer server;
    private final Path logDir;
    private final int port;

    private EmbeddedKafkaBroker(KafkaRaftServer server, Path logDir, int port) {
        this.server = server;
        this.logDir = logDir;
        this.port = port;
    }

    /** Bootstrap address for KafkaProducer / KafkaConsumer configuration. */
    public String bootstrapServers() {
        return "localhost:" + port;
    }

    /**
     * Starts an embedded broker only when no external Kafka is configured.
     *
     * Returns null if KAFKA_BOOTSTRAP_SERVERS env var is set (external broker in use).
     * Returns a running EmbeddedKafkaBroker otherwise.
     */
    public static EmbeddedKafkaBroker startIfNeeded() throws Exception {
        String external = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (external != null && !external.isBlank()) {
            log.info("Using external Kafka: {}", external);
            return null;
        }
        log.info("No KAFKA_BOOTSTRAP_SERVERS set — starting embedded Kafka broker");
        return start();
    }

    /**
     * Starts an embedded Kafka broker on a random free port.
     * Blocks until the broker is ready to accept connections.
     */
    public static EmbeddedKafkaBroker start() throws Exception {
        int port = findFreePort();
        Path logDir = Files.createTempDirectory("embedded-kafka-");

        String clusterId = Uuid.randomUuid().toString();
        Properties props = buildKraftConfig(port, logDir, clusterId);

        // Write config to a temp file (KafkaRaftServer reads from file)
        File configFile = logDir.resolve("server.properties").toFile();
        try (FileWriter fw = new FileWriter(configFile)) {
            props.store(fw, "Embedded Kafka KRaft config");
        }

        // Format the storage directory with the cluster ID
        formatStorage(logDir, clusterId, configFile);

        KafkaConfig kafkaConfig = new KafkaConfig(props, true);
        KafkaRaftServer server = new KafkaRaftServer(kafkaConfig, Time.SYSTEM);
        server.startup();

        log.info("Embedded Kafka broker started: port={}, logDir={}", port, logDir);

        // Wait until the broker is actually accepting connections
        waitForPort(port, 30_000);

        // Publish the address so KafkaConfig.bootstrapServers() picks it up
        System.setProperty("kafka.bootstrap.servers", "localhost:" + port);

        return new EmbeddedKafkaBroker(server, logDir, port);
    }

    @Override
    public void close() {
        log.info("Shutting down embedded Kafka broker...");
        try {
            server.shutdown();
            server.awaitShutdown();
        } catch (Exception e) {
            log.warn("Error during Kafka broker shutdown", e);
        }
        deleteDir(logDir);
        log.info("Embedded Kafka broker stopped");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static Properties buildKraftConfig(int port, Path logDir, String clusterId) {
        Properties p = new Properties();
        p.setProperty("node.id", "1");
        p.setProperty("process.roles", "broker,controller");
        p.setProperty("listeners",
                "PLAINTEXT://localhost:" + port + ",CONTROLLER://localhost:" + (port + 1));
        p.setProperty("advertised.listeners", "PLAINTEXT://localhost:" + port);
        p.setProperty("controller.quorum.voters", "1@localhost:" + (port + 1));
        p.setProperty("controller.listener.names", "CONTROLLER");
        p.setProperty("listener.security.protocol.map",
                "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT");
        p.setProperty("inter.broker.listener.name", "PLAINTEXT");
        p.setProperty("log.dirs", logDir.toAbsolutePath().toString());
        p.setProperty("offsets.topic.replication.factor", "1");
        p.setProperty("transaction.state.log.replication.factor", "1");
        p.setProperty("transaction.state.log.min.isr", "1");
        p.setProperty("auto.create.topics.enable", "true");
        p.setProperty("num.partitions", "1");
        p.setProperty("default.replication.factor", "1");
        // Reduce log noise
        p.setProperty("log.retention.check.interval.ms", "300000");
        p.setProperty("log.segment.bytes", "1073741824");
        return p;
    }

    private static void formatStorage(Path logDir, String clusterId, File configFile)
            throws Exception {
        // kafka-storage.sh format equivalent via StorageTool
        String[] formatArgs = {
            "format",
            "--cluster-id", clusterId,
            "--config", configFile.getAbsolutePath(),
            "--standalone"
        };
        kafka.tools.StorageTool$.MODULE$.main(formatArgs);
        log.info("Kafka storage formatted: clusterId={}", clusterId);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    private static void waitForPort(int port, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket("localhost", port)) {
                return; // connected — broker is up
            } catch (IOException e) {
                Thread.sleep(200);
            }
        }
        throw new RuntimeException("Embedded Kafka did not start within " + timeoutMs + "ms");
    }

    private static void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        } catch (IOException e) {
            log.warn("Could not delete temp dir {}: {}", dir, e.getMessage());
        }
    }
}
