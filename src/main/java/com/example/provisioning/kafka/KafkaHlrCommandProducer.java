package com.example.provisioning.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.Future;

/**
 * Publishes HLR change commands to the hlr-commands Kafka topic.
 *
 * Each message is keyed by ICCID so all commands for the same SIM
 * land on the same partition, preserving ordering per SIM.
 *
 * The producer is thread-safe and should be shared across activity invocations.
 * Call close() when the worker shuts down.
 */
public class KafkaHlrCommandProducer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaHlrCommandProducer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KafkaProducer<String, String> producer;

    public KafkaHlrCommandProducer() {
        this(KafkaConfig.bootstrapServers());
    }

    public KafkaHlrCommandProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Idempotent producer — prevents duplicate messages on retries
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, "3");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "hlr-command-producer");
        this.producer = new KafkaProducer<>(props);
        log.info("KafkaHlrCommandProducer initialized: bootstrapServers={}", bootstrapServers);
    }

    /**
     * Publishes an HLR command message and waits for broker acknowledgement.
     *
     * @param message the command to publish
     * @return the Kafka offset the message was written to
     */
    public long publish(HlrCommandMessage message) {
        try {
            String json = MAPPER.writeValueAsString(message);
            // Key by ICCID — ensures ordering per SIM card
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaConfig.HLR_COMMANDS_TOPIC,
                    message.getIccid(),
                    json
            );

            log.info("[{}] Publishing HLR command to topic={}: {}",
                    message.getCorrelationId(), KafkaConfig.HLR_COMMANDS_TOPIC, message);

            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get(); // synchronous — activity handles retries

            log.info("[{}] HLR command published: topic={}, partition={}, offset={}",
                    message.getCorrelationId(),
                    metadata.topic(), metadata.partition(), metadata.offset());

            return metadata.offset();

        } catch (Exception e) {
            throw new RuntimeException("Failed to publish HLR command for correlationId="
                    + message.getCorrelationId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        log.info("Closing KafkaHlrCommandProducer");
        producer.close();
    }
}
