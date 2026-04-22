package com.example.provisioning.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Kafka-backed HLR message bus.
 *
 * Used when KAFKA_BOOTSTRAP_SERVERS is set (Docker / external Kafka available).
 * Each instance creates its own producer and consumer pair.
 * Consumers use unique group IDs so every instance receives all messages
 * (fan-out semantics needed for the simulator and the confirmation consumer).
 */
public class KafkaHlrBus implements HlrBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaHlrBus.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> commandConsumer;
    private final KafkaConsumer<String, String> confirmationConsumer;

    public KafkaHlrBus(String bootstrapServers, String consumerGroupSuffix) {
        this.producer = buildProducer(bootstrapServers);

        // Unique group per instance so simulator and worker each get all messages
        String groupId = "hlr-bus-" + consumerGroupSuffix + "-" + UUID.randomUUID().toString().substring(0, 6);
        this.commandConsumer      = buildConsumer(bootstrapServers, groupId + "-cmd");
        this.confirmationConsumer = buildConsumer(bootstrapServers, groupId + "-conf");

        commandConsumer.subscribe(Collections.singletonList(KafkaConfig.HLR_COMMANDS_TOPIC));
        confirmationConsumer.subscribe(Collections.singletonList(KafkaConfig.HLR_CONFIRMATIONS_TOPIC));

        log.info("KafkaHlrBus initialized: bootstrapServers={}", bootstrapServers);
    }

    @Override
    public long publishCommand(HlrCommandMessage message) {
        return publish(KafkaConfig.HLR_COMMANDS_TOPIC, message.getIccid(), message);
    }

    @Override
    public void publishConfirmation(HlrConfirmationMessage message) {
        publish(KafkaConfig.HLR_CONFIRMATIONS_TOPIC, message.getCorrelationId(), message);
    }

    @Override
    public HlrCommandMessage pollCommand(long timeoutMs) throws InterruptedException {
        ConsumerRecords<String, String> records = commandConsumer.poll(Duration.ofMillis(timeoutMs));
        for (ConsumerRecord<String, String> r : records) {
            commandConsumer.commitSync();
            return parse(r.value(), HlrCommandMessage.class);
        }
        return null;
    }

    @Override
    public HlrConfirmationMessage pollConfirmation(long timeoutMs) throws InterruptedException {
        ConsumerRecords<String, String> records = confirmationConsumer.poll(Duration.ofMillis(timeoutMs));
        for (ConsumerRecord<String, String> r : records) {
            confirmationConsumer.commitSync();
            return parse(r.value(), HlrConfirmationMessage.class);
        }
        return null;
    }

    @Override
    public void close() {
        producer.close();
        commandConsumer.close();
        confirmationConsumer.close();
        log.info("KafkaHlrBus closed");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long publish(String topic, String key, Object payload) {
        try {
            String json = MAPPER.writeValueAsString(payload);
            Future<RecordMetadata> f = producer.send(new ProducerRecord<>(topic, key, json));
            return f.get().offset();
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish to " + topic + ": " + e.getMessage(), e);
        }
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse message: " + e.getMessage(), e);
        }
    }

    private static KafkaProducer<String, String> buildProducer(String bootstrapServers) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.RETRIES_CONFIG, "3");
        return new KafkaProducer<>(p);
    }

    private static KafkaConsumer<String, String> buildConsumer(String bootstrapServers, String groupId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        return new KafkaConsumer<>(p);
    }
}
