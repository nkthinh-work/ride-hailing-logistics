package com.rhl.trip.services;

import com.rhl.trip.entities.Trip;
import com.rhl.trip.models.TripStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Default implementation of CancellationPolicyService (M05 Group 3, FR-CAN-003).
 *
 * POLICY STATUS: BUSINESS_POLICY_PENDING
 * - Free cancellation applies for CREATED and MATCHING states (driver not yet dispatched).
 * - Administrative/Support cancellations apply no customer penalty by default.
 * - For ACCEPTED, PICKING_UP, ARRIVED: The official financial schedule (grace period, fee amounts,
 *   driver compensation split) is pending business approval and Payment Service integration (Module 08).
 *   Returns BigDecimal.ZERO until official fee policy is published.
 */
@Service
public class DefaultCancellationPolicyService implements CancellationPolicyService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCancellationPolicyService.class);

    @Override
    public BigDecimal calculateCancellationFee(
            Trip trip,
            UUID actorId,
            String actorRole,
            String reasonCode,
            Instant now) {

        TripStatus status = trip.getStatus();

        // 1. CREATED and MATCHING: Free cancellation (no driver has committed)
        if (status == TripStatus.CREATED || status == TripStatus.MATCHING) {
            log.debug("Free cancellation applied for tripId={} in status={}", trip.getId(), status);
            return BigDecimal.ZERO;
        }

        // 2. SUPPORT_STAFF and ADMINISTRATOR: Operational exception workflows
        if ("SUPPORT_STAFF".equalsIgnoreCase(actorRole) || "ADMINISTRATOR".equalsIgnoreCase(actorRole)) {
            log.info("Administrative override cancellation for tripId={} by actorRole={}. Fee: 0",
                    trip.getId(), actorRole);
            return BigDecimal.ZERO;
        }

        // 3. For post-assignment states (ACCEPTED, PICKING_UP, ARRIVED):
        // Note: Actual fee debit/credit requires Payment Service.
        // Marking as FEE_POLICY_PENDING / returning ZERO without assuming arbitrary penalty.
        log.info("Cancellation fee for tripId={} in status={} by actorRole={} calculated as 0 (FEE_POLICY_PENDING)",
                trip.getId(), status, actorRole);
        return BigDecimal.ZERO;
    }
}
