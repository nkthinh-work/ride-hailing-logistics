package com.rhl.wallet.domain;

import com.rhl.wallet.exceptions.InsufficientLedgerBalanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DriverWallet Aggregate Domain Tests")
class DriverWalletTest {

    private final UUID driverId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @Test
    @DisplayName("Creation initializes zero balances, ACTIVE status and VND currency")
    void createWallet_initializesCorrectly() {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", now);

        assertThat(wallet.getId()).isNotNull();
        assertThat(wallet.getDriverId()).isEqualTo(driverId);
        assertThat(wallet.getCurrency()).isEqualTo("VND");
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getPendingBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("creditAvailable increases available balance correctly")
    void creditAvailable_increasesBalance() {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", now);
        wallet.creditAvailable(new BigDecimal("100000.00"), now);

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    @Test
    @DisplayName("debitAvailable decreases available balance correctly")
    void debitAvailable_decreasesBalance() {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", now);
        wallet.creditAvailable(new BigDecimal("100000.00"), now);
        wallet.debitAvailable(new BigDecimal("40000.00"), now);

        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("60000.00"));
    }

    @Test
    @DisplayName("debitAvailable exceeding balance throws InsufficientLedgerBalanceException (FR-WAL-009)")
    void debitAvailable_exceedingBalance_throwsInsufficientLedgerBalanceException() {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", now);
        wallet.creditAvailable(new BigDecimal("50000.00"), now);

        assertThatThrownBy(() -> wallet.debitAvailable(new BigDecimal("60000.00"), now))
                .isInstanceOf(InsufficientLedgerBalanceException.class)
                .hasMessageContaining("Cannot debit 60000.00 VND from available balance 50000.00");
    }

    @Test
    @DisplayName("Negative amount credit or debit throws IllegalArgumentException")
    void negativeAmount_throwsIllegalArgumentException() {
        DriverWallet wallet = DriverWallet.create(driverId, "VND", now);

        assertThatThrownBy(() -> wallet.creditAvailable(new BigDecimal("-100.00"), now))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> wallet.debitAvailable(BigDecimal.ZERO, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
