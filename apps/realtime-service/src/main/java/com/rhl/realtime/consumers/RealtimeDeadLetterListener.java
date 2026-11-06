package com.rhl.realtime.consumers;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audit listener for Dead Letter Topics (DLT).
 * Captures poison pills and malformed events from both dispatch and trip topics
 * along with origin partition, offset, and failure exception.
 */
@Component
public class RealtimeDeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeDeadLetterListener.class);

    private final AtomicLong deadLetterCount = new AtomicLong(0);

    @KafkaListener(
            topics = {
                    "${realtime.kafka.topic.dispatch-offer:rhl.dispatch.offer.v1}.dlt",
                    "${realtime.kafka.topic.trip-lifecycle:rhl.trip.lifecycle.v1}.dlt"
            },
            groupId = "${realtime.kafka.group.dlt:rhl.realtime.dlt.v1}"
    )
    public void consumeDeadLetter(
            ConsumerRecord<String, String> record,
            @Header(value = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            @Header(value = "kafka_dlt-original-topic", required = false) byte[] originalTopicBytes,
            @Header(value = "kafka_dlt-exception-message", required = false) byte[] exceptionMessageBytes
    ) {
        deadLetterCount.incrementAndGet();

        String originalTopic = originalTopicBytes != null ? new String(originalTopicBytes, StandardCharsets.UTF_8) : "unknown";
        String exceptionMessage = exceptionMessageBytes != null ? new String(exceptionMessageBytes, StandardCharsets.UTF_8) : "unknown";

        log.warn("DEAD-LETTER RECEIVED [totalCount={}]: dltTopic={}, originalTopic={}, key={}, partition={}, offset={}, exception={}",
                deadLetterCount.get(),
                topic,
                originalTopic,
                record.key(),
                record.partition(),
                record.offset(),
                exceptionMessage);
    }

    public long getDeadLetterCount() {
        return deadLetterCount.get();
    }
}
