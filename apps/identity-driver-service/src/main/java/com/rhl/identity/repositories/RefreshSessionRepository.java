
package com.rhl.identity.repositories;

import com.rhl.identity.entities.RefreshSession;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionRepository
        extends JpaRepository<RefreshSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM RefreshSession s
            WHERE s.tokenHash = :hash
            """)
    Optional<RefreshSession> findByHashForUpdate(
            @Param("hash") String hash
    );
}
