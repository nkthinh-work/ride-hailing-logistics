package com.rhl.identity.entities;

import com.rhl.identity.entities.enums.DriverAuditAction;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "driver_audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriverAuditLog {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_profile_id", nullable = false)
    private DriverProfile driverProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DriverAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30)
    private DriverApplicationStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 30)
    private DriverApplicationStatus newStatus;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public DriverAuditLog(
            DriverProfile driverProfile,
            User actor,
            DriverAuditAction action,
            DriverApplicationStatus previousStatus,
            DriverApplicationStatus newStatus,
            String reason) {

        this.id = UUID.randomUUID();
        this.driverProfile = driverProfile;
        this.actor = actor;
        this.action = action;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.createdAt = Instant.now();
    }
}
