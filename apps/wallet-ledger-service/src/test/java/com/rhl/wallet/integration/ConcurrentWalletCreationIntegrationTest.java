package com.rhl.wallet.integration;

import com.rhl.wallet.domain.DriverWallet;
import com.rhl.wallet.repositories.DriverWalletRepository;
import com.rhl.wallet.services.DriverWalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf("isDockerAvailable")
@DisplayName("Concurrent Wallet Creation Race Condition Test on PostgreSQL Testcontainers")
class ConcurrentWalletCreationIntegrationTest {

    static PostgreSQLContainer postgres;

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (isDockerAvailable()) {
            if (postgres == null) {
                postgres = new PostgreSQLContainer("postgres:15-alpine");
                postgres.start();
            }
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        }
    }

    @Autowired
    private DriverWalletService walletService;

    @Autowired
    private DriverWalletRepository walletRepository;

    @Test
    @DisplayName("10 concurrent threads creating wallet for same driver produce exactly 1 wallet in DB")
    void concurrentWalletCreation_producesSingleWallet() throws Exception {
        UUID driverId = UUID.randomUUID();
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        Set<UUID> resultingWalletIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    DriverWallet wallet = walletService.getOrCreateWallet(driverId, "VND");
                    resultingWalletIds.add(wallet.getId());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(resultingWalletIds).hasSize(1);

        // Verify exactly one wallet exists in database
        List<DriverWallet> wallets = walletRepository.findAll().stream()
                .filter(w -> w.getDriverId().equals(driverId))
                .toList();

        assertThat(wallets).hasSize(1);
        assertThat(wallets.getFirst().getId()).isEqualTo(resultingWalletIds.iterator().next());
    }
}
