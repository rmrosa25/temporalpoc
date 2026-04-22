package com.example.provisioning.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the appropriate {@link HlrBus} implementation based on environment.
 *
 * If KAFKA_BOOTSTRAP_SERVERS is set, returns a {@link KafkaHlrBus}.
 * Otherwise returns an {@link InProcessHlrBus} (no external dependencies).
 *
 * The bus instance must be shared across the Worker, CspChangeActivitiesImpl,
 * HlrConfirmationDispatcher, and KafkaSimulator within the same JVM so they
 * all read from and write to the same queues.
 */
public final class HlrBusFactory {

    private static final Logger log = LoggerFactory.getLogger(HlrBusFactory.class);

    // Singleton shared within the JVM — set once at startup by Worker or TestRunner
    private static volatile HlrBus instance;

    private HlrBusFactory() {}

    /**
     * Returns the shared bus instance, creating it if necessary.
     * Thread-safe via double-checked locking.
     */
    public static HlrBus get() {
        if (instance == null) {
            synchronized (HlrBusFactory.class) {
                if (instance == null) {
                    instance = create();
                }
            }
        }
        return instance;
    }

    /** Replaces the shared instance. Used in tests to inject a specific bus. */
    public static void set(HlrBus bus) {
        synchronized (HlrBusFactory.class) {
            instance = bus;
        }
    }

    /** Closes and clears the shared instance. */
    public static void close() {
        synchronized (HlrBusFactory.class) {
            if (instance != null) {
                instance.close();
                instance = null;
            }
        }
    }

    private static HlrBus create() {
        String external = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (external != null && !external.isBlank()) {
            log.info("HlrBus: using Kafka at {}", external);
            return new KafkaHlrBus(external, "worker");
        }
        log.info("HlrBus: using in-process bus (no KAFKA_BOOTSTRAP_SERVERS set)");
        return new InProcessHlrBus();
    }
}
