package com.rhl.wallet.integration;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsResult;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.Socket;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Live Docker Kafka KRaft Broker Verification Test (:9094)")
class LiveDockerKafkaE2ETest {

    static boolean isLiveKafkaAvailable() {
        try (Socket socket = new Socket("localhost", 9094)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("isLiveKafkaAvailable")
    @DisplayName("Live Docker Kafka: Verifies broker metadata, topic existence, produce and consume on localhost:9094")
    void liveDockerKafka_produceAndConsumeVerified() throws Exception {
        String bootstrap = "localhost:9094";

        // 1. Verify AdminClient connectivity & topic catalog
        Map<String, Object> adminProps = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (AdminClient admin = AdminClient.create(adminProps)) {
            ListTopicsResult topics = admin.listTopics();
            Set<String> topicNames = topics.names().get(5, TimeUnit.SECONDS);
            assertThat(topicNames).contains("rhl.payment.events.v1");
        }

        // 2. Real Producer verification with acks=all
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String testPayload = """
                {
                  "eventId": "%s",
                  "eventType": "PAYMENT_SUCCEEDED",
                  "eventVersion": 1,
                  "occurredAt": "2026-10-27T10:30:00Z",
                  "correlationId": "corr-live-docker",
                  "entityId": "%s",
                  "payload": {
                    "paymentId": "%s",
                    "amount": 90000,
                    "currency": "VND",
                    "paymentMethod": "CASH"
                  }
                }
                """.formatted(eventId, paymentId, paymentId);

        Map<String, Object> prodProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.ACKS_CONFIG, "all"
        );

        RecordMetadata metadata;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(prodProps)) {
            ProducerRecord<String, String> record = new ProducerRecord<>("rhl.payment.events.v1", paymentId.toString(), testPayload);
            Future<RecordMetadata> future = producer.send(record);
            metadata = future.get(5, TimeUnit.SECONDS);
        }

        assertThat(metadata).isNotNull();
        assertThat(metadata.topic()).isEqualTo("rhl.payment.events.v1");
        assertThat(metadata.offset()).isGreaterThanOrEqualTo(0L);

        // 3. Real Consumer verification
        String groupId = "live-verifier-" + UUID.randomUUID();
        Map<String, Object> consProps = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        );

        boolean found = false;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consProps)) {
            consumer.subscribe(Collections.singletonList("rhl.payment.events.v1"));
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline && !found) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (r.value().contains(eventId.toString())) {
                        found = true;
                        assertThat(r.key()).isEqualTo(paymentId.toString());
                        break;
                    }
                }
            }
        }

        assertThat(found).as("Published message must be received from live Docker Kafka").isTrue();
    }
}
