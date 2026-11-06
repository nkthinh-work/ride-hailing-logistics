
package com.rhl.identity.repositories;

import com.rhl.identity.entities.DriverProfile;

import com.rhl.identity.entities.enums.DriverApplicationStatus;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverProfileRepository
        extends JpaRepository<DriverProfile, UUID> {

    boolean existsByUser_Id(UUID userId);

    Optional<DriverProfile> findByUser_Id(UUID userId);

    List<DriverProfile> findByStatus(
            DriverApplicationStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT d
            FROM DriverProfile d
            WHERE d.id = :id
            """)
    Optional<DriverProfile> findByIdForUpdate(
            @Param("id") UUID id
    );
}
