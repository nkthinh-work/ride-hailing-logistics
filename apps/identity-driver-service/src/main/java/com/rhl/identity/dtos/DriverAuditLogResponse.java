package com.rhl.identity.dtos;

import com.rhl.identity.entities.enums.DriverAuditAction;
import com.rhl.identity.entities.enums.DriverApplicationStatus;

import java.time.Instant;
import java.util.UUID;

public record DriverAuditLogResponse(
        UUID id,
        UUID driverProfileId,
        UUID actorId,
        DriverAuditAction action,
        DriverApplicationStatus previousStatus,
        DriverApplicationStatus newStatus,
        String reason,
        Instant createdAt
) {}
