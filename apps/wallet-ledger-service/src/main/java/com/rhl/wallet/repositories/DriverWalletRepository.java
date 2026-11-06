package com.rhl.wallet.repositories;

import com.rhl.wallet.domain.DriverWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DriverWalletRepository extends JpaRepository<DriverWallet, UUID> {

    Optional<DriverWallet> findByDriverId(UUID driverId);

    Optional<DriverWallet> findByDriverIdAndCurrency(UUID driverId, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM DriverWallet w WHERE w.id = :id")
    Optional<DriverWallet> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM DriverWallet w WHERE w.driverId = :driverId")
    Optional<DriverWallet> findByDriverIdForUpdate(@Param("driverId") UUID driverId);
}
