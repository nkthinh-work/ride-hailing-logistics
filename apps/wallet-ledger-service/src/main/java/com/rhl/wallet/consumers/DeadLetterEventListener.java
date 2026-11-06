package com.rhl.wallet.consumers;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Audit listener for Dead Letter Topic rhl.payment.events.v1.dlt (CON-07, FR-EVT-001..008).
 * Logs poison pills and permanently rejected events with original failure causes
 * for administrative inspection and controlled replay.
 */
@Component
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterEventListener.class);

    @KafkaListener(
            topics = "${kafka.topics.payment-events-dlt:rhl.payment.events.v1.dlt}",
            groupId = "${kafka.consumer.group-id:rhl.wallet-ledger.v1}-dlt"
    )
    public void onDeadLetter(
            ConsumerRecord<String, String> record,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) String originalTopic,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String exceptionMessage
    ) {
        log.error("DEAD-LETTER RECORD AUDITED: key={}, partition={}, offset={}, originalTopic={}, reason={}, payload={}",
                record.key(), record.partition(), record.offset(), originalTopic, exceptionMessage, record.value());
    }
}
