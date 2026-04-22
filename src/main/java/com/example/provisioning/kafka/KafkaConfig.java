package com.example.provisioning.kafka;

/**
 * Provisioning channel topic name constants.
 *
 * These names document the Kafka topics that would be used in a production
 * deployment. The current implementation uses {@link InProcessHlrBus} instead.
 */
public final class KafkaConfig {

    private KafkaConfig() {}

    /** Commands published by provisioningCommand activity, one per NetworkElement. */
    public static final String PROVISIONING_COMMANDS_TOPIC = "provisioning-commands";

    /** Confirmations published by network element adapters, one per NetworkElement. */
    public static final String PROVISIONING_CONFIRMATIONS_TOPIC = "provisioning-confirmations";
}
