package com.rhl.identity.entities;

import com.rhl.identity.entities.enums.VehicleStatus;
import com.rhl.identity.entities.enums.VehicleType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Vehicle {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_profile_id", nullable = false)
    private DriverProfile driverProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VehicleType type;

    @Column(nullable = false, unique = true, length = 20)
    private String plate;

    @Column(nullable = false, length = 50)
    private String make;

    @Column(nullable = false, length = 50)
    private String model;

    @Column(nullable = false, length = 30)
    private String color;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Vehicle(
            DriverProfile driverProfile,
            VehicleType type,
            String plate,
            String make,
            String model,
            String color,
            VehicleStatus status
    ) {
        this.id = UUID.randomUUID();
        this.driverProfile = driverProfile;
        this.type = type;
        this.plate = plate;
        this.make = make;
        this.model = model;
        this.color = color;
        this.status = status != null ? status : VehicleStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public void updateStatus(VehicleStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }
}
