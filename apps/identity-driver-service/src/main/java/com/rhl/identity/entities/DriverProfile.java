
package com.rhl.identity.entities;

import com.rhl.identity.entities.enums.DriverApplicationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "driver_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriverProfile {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "driver_license_number",
            nullable = false, unique = true, length = 50)
    private String driverLicenseNumber;

    @Column(name = "vehicle_plate", nullable = false, length = 20)
    private String vehiclePlate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DriverApplicationStatus status;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @OneToMany(mappedBy = "driverProfile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Vehicle> vehicles = new ArrayList<>();

    public DriverProfile(
            User user,
            String fullName,
            String phone,
            String driverLicenseNumber,
            String vehiclePlate) {

        this.id = UUID.randomUUID();
        this.user = user;
        this.fullName = fullName;
        this.phone = phone;
        this.driverLicenseNumber = driverLicenseNumber;
        this.vehiclePlate = vehiclePlate;
        this.status = DriverApplicationStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void approve(User reviewer) {
        this.status = DriverApplicationStatus.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.rejectionReason = null;
    }

    public void reject(User reviewer, String reason) {
        this.status = DriverApplicationStatus.REJECTED;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.rejectionReason = reason;
    }

    public void suspend() {
        this.status = DriverApplicationStatus.SUSPENDED;
    }

    public void reactivate() {
        this.status = DriverApplicationStatus.APPROVED;
    }

    public void addVehicle(Vehicle vehicle) {
        this.vehicles.add(vehicle);
    }
}
