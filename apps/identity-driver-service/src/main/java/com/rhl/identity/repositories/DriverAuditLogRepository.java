package com.rhl.identity.repositories;

import com.rhl.identity.entities.DriverAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DriverAuditLogRepository extends JpaRepository<DriverAuditLog, UUID> {

    List<DriverAuditLog> findByDriverProfile_IdOrderByCreatedAtDesc(UUID driverProfileId);
}
