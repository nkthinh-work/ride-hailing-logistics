package com.rhl.payment.services;

import com.rhl.payment.events.FinancialEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Test fixture / stub consumer for financial events used only in test scope.
 * Removed from production src/main to prevent silent no-op message drops.
 */
@Component
@ConditionalOnMissingBean(FinancialEventConsumer.class)
public class DefaultFinancialEventConsumer implements FinancialEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DefaultFinancialEventConsumer.class);

    @Override
    public void onEvent(FinancialEventEnvelope<?> envelope) {
        log.info("Test stub consumed financial outbox event: id={}, type={}, entityId={}",
                envelope.eventId(), envelope.eventType(), envelope.entityId());
    }
}
