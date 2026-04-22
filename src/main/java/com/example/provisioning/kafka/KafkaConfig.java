package com.example.provisioning.kafka;

/**
 * Shared Kafka topic names and configuration constants.
 *
 * Topic flow:
 *
 *   [CspChangeWorkflow]
 *        │
 *        │ publishHlrCommand activity
 *        ▼
 *   hlr-commands          ← producer writes HlrCommandMessage here
 *        │
 *        │ (HLR gateway consumes, applies profile change in network)
 *        ▼
 *   hlr-confirmations     ← HLR gateway (or KafkaSimulator in tests) writes HlrConfirmationMessage
 *        │
 *        │ KafkaHlrConfirmationConsumer reads
 *        ▼
 *   Temporal signal → CspChangeWorkflow.hlrConfirmationReceived()
 */
public final class KafkaConfig {

    private KafkaConfig() {}

    /** Topic the worker publishes HLR change commands to. */
    public static final String HLR_COMMANDS_TOPIC = "hlr-commands";

    /** Topic the HLR gateway (or simulator) publishes confirmations to. */
    public static final String HLR_CONFIRMATIONS_TOPIC = "hlr-confirmations";

    /** Consumer group for the HLR confirmation consumer. */
    public static final String HLR_CONSUMER_GROUP = "hlr-confirmation-consumer";

    /** Default bootstrap servers — overridden by KAFKA_BOOTSTRAP_SERVERS env var or system property. */
    public static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";

    /**
     * Returns the Kafka bootstrap address.
     *
     * Resolution order:
     *   1. KAFKA_BOOTSTRAP_SERVERS environment variable (set externally or by run.sh)
     *   2. kafka.bootstrap.servers system property (set by EmbeddedKafkaBroker at runtime)
     *   3. DEFAULT_BOOTSTRAP_SERVERS fallback
     */
    public static String bootstrapServers() {
        String fromEnv = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        String fromProp = System.getProperty("kafka.bootstrap.servers");
        if (fromProp != null && !fromProp.isBlank()) return fromProp;
        return DEFAULT_BOOTSTRAP_SERVERS;
    }
}
