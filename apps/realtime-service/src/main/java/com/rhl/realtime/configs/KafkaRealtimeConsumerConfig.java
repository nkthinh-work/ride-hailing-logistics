package com.rhl.realtime.configs;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Apache Kafka Consumer configuration for Realtime Service.
 * Configures manual/record offset commit after successful STOMP dispatch,
 * 3-attempt fixed backoff, and Dead Letter Topic routing for poison messages.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "realtime.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaRealtimeConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaRealtimeConsumerConfig.class);

    @Value("${spring.kafka.bootstrap-servers:${kafka.bootstrap-servers:127.0.0.1:9094}}")
    private String bootstrapServers;

    @Value("${realtime.kafka.group.default:rhl.realtime.v1}")
    private String defaultGroupId;

    @Value("${realtime.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Bean
    public ConsumerFactory<String, String> realtimeConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, defaultGroupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ProducerFactory<String, String> realtimeDltProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> realtimeDltKafkaTemplate() {
        return new KafkaTemplate<>(realtimeDltProducerFactory());
    }

    @Bean
    public DeadLetterPublishingRecoverer realtimeDeadLetterPublishingRecoverer(KafkaTemplate<String, String> realtimeDltKafkaTemplate) {
        return new DeadLetterPublishingRecoverer(realtimeDltKafkaTemplate,
                (record, ex) -> {
                    String targetDlt = record.topic() + ".dlt";
                    log.error("Routing failed record from topic {} partition {} offset {} to DLT {} due to: {}",
                            record.topic(), record.partition(), record.offset(), targetDlt, ex.getMessage());
                    return new TopicPartition(targetDlt, record.partition());
                });
    }

    @Bean
    public DefaultErrorHandler realtimeKafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        FixedBackOff backOff = new FixedBackOff(1000L, 2L);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                IllegalStateException.class
        );
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> realtimeConsumerFactory,
            DefaultErrorHandler realtimeKafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(realtimeConsumerFactory);
        factory.setCommonErrorHandler(realtimeKafkaErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
