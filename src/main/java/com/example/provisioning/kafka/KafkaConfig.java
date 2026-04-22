package com.example.provisioning.kafka;

/**
 * Shared Kafka topic names and configuration constants.
 *
 * Topic flow:
 *
 *   [CspChangeWorkflow]
 *        │ provisioningCommand activity (one message per NetworkElement)
 *        ▼
 *   provisioning-commands     ← ProvisioningCommandMessage per element
 *        │
 *        │ (network element adapters consume, apply profile change)
 *        ▼
 *   provisioning-confirmations ← ElementConfirmationMessage per element
 *        │
 *        │ HlrConfirmationDispatcher reads, signals workflow
 *        ▼
 *   CspChangeWorkflow.elementConfirmationReceived() [one signal per element]
 */
public final class KafkaConfig {

    private KafkaConfig() {}

    public static final String PROVISIONING_COMMANDS_TOPIC      = "provisioning-commands";
    public static final String PROVISIONING_CONFIRMATIONS_TOPIC = "provisioning-confirmations";

    public static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";

    /**
     * Resolution order:
     *   1. KAFKA_BOOTSTRAP_SERVERS env var
     *   2. kafka.bootstrap.servers system property (set by EmbeddedKafkaBroker)
     *   3. DEFAULT_BOOTSTRAP_SERVERS
     */
    public static String bootstrapServers() {
        String fromEnv = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        String fromProp = System.getProperty("kafka.bootstrap.servers");
        if (fromProp != null && !fromProp.isBlank()) return fromProp;
        return DEFAULT_BOOTSTRAP_SERVERS;
    }
}
