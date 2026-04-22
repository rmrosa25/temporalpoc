package com.example.provisioning.kafka;

/**
 * Holds the shared {@link HlrBus} instance for the current JVM.
 *
 * Always returns an {@link InProcessHlrBus}. In a production deployment
 * this would be replaced with a Kafka-backed implementation.
 */
public final class HlrBusFactory {

    private static volatile HlrBus instance;

    private HlrBusFactory() {}

    public static HlrBus get() {
        if (instance == null) {
            synchronized (HlrBusFactory.class) {
                if (instance == null) {
                    instance = new InProcessHlrBus();
                }
            }
        }
        return instance;
    }

    public static void close() {
        synchronized (HlrBusFactory.class) {
            if (instance != null) {
                instance.close();
                instance = null;
            }
        }
    }
}
