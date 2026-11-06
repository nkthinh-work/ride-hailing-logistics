package com.rhl.pricing.entities;

import com.rhl.pricing.models.FareBreakdown;
import com.rhl.pricing.models.ServiceType;
import com.rhl.pricing.models.VehicleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FareQuote Entity Tests")
class FareQuoteTest {

    private FareBreakdown sampleBreakdown() {
        return new FareBreakdown(
                new BigDecimal("12000.00"),
                new BigDecimal("2.00"),
                new BigDecimal("3.00"),
                new BigDecimal("13500.00"),
                new BigDecimal("10.00"),
                new BigDecimal("4000.00"),
                new BigDecimal("29500.00"),
                new BigDecimal("12000.00"),
                new BigDecimal("29500.00"),
                new BigDecimal("1.0000"),
                new BigDecimal("29500.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("29500"),
                "VND",
                "v1"
        );
    }

    @Nested
    @DisplayName("Expiration and Ownership Tests")
    class ExpirationAndOwnershipTests {

        @Test
        @DisplayName("Should correctly evaluate quote expiration using server timestamp")
        void shouldEvaluateExpiration() {
            Instant createdAt = Instant.parse("2026-10-23T10:00:00Z");
            Instant expiresAt = Instant.parse("2026-10-23T10:05:00Z");
            UUID customerId = UUID.randomUUID();

            FareQuote quote = new FareQuote(
                    UUID.randomUUID(),
                    customerId,
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("10.7769"),
                    new BigDecimal("106.7009"),
                    "Saigon Opera House",
                    new BigDecimal("10.7725"),
                    new BigDecimal("106.6980"),
                    "Ben Thanh Market",
                    5000L,
                    600L,
                    "OSRM",
                    false,
                    "RULE-001",
                    "v1",
                    sampleBreakdown(),
                    createdAt,
                    expiresAt
            );

            // Exactly at created time: NOT expired
            assertThat(quote.isExpired(createdAt)).isFalse();

            // 4 minutes later: NOT expired
            assertThat(quote.isExpired(createdAt.plusSeconds(240))).isFalse();

            // Exactly at expiresAt: EXPIRED (now >= expiresAt)
            assertThat(quote.isExpired(expiresAt)).isTrue();

            // 1 second after expiresAt: EXPIRED
            assertThat(quote.isExpired(expiresAt.plusSeconds(1))).isTrue();
        }

        @Test
        @DisplayName("Should correctly verify customer ownership")
        void shouldVerifyCustomerOwnership() {
            UUID ownerId = UUID.randomUUID();
            UUID strangerId = UUID.randomUUID();

            FareQuote quote = new FareQuote(
                    UUID.randomUUID(),
                    ownerId,
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("10.7769"),
                    new BigDecimal("106.7009"),
                    "Pickup",
                    new BigDecimal("10.7725"),
                    new BigDecimal("106.6980"),
                    "Dropoff",
                    5000L,
                    600L,
                    "OSRM",
                    false,
                    "RULE-001",
                    "v1",
                    sampleBreakdown(),
                    Instant.now(),
                    Instant.now().plusSeconds(300)
            );

            assertThat(quote.isOwnedBy(ownerId)).isTrue();
            assertThat(quote.isOwnedBy(strangerId)).isFalse();
            assertThat(quote.isOwnedBy(null)).isFalse();
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException if expiresAt is not after createdAt")
        void shouldRejectInvalidExpirationBounds() {
            Instant now = Instant.now();
            UUID customerId = UUID.randomUUID();

            assertThatThrownBy(() -> new FareQuote(
                    UUID.randomUUID(),
                    customerId,
                    ServiceType.RIDE,
                    VehicleType.MOTORBIKE,
                    "DEFAULT",
                    new BigDecimal("10.7769"),
                    new BigDecimal("106.7009"),
                    "Pickup",
                    new BigDecimal("10.7725"),
                    new BigDecimal("106.6980"),
                    "Dropoff",
                    5000L,
                    600L,
                    "OSRM",
                    false,
                    "RULE-001",
                    "v1",
                    sampleBreakdown(),
                    now,
                    now // expiresAt == createdAt
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expiresAt must be after createdAt");
        }
    }
}
