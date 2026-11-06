package com.rhl.identity.repositories;

import com.rhl.identity.entities.Vehicle;
import com.rhl.identity.entities.enums.VehicleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    boolean existsByPlateIgnoreCase(String plate);

    Optional<Vehicle> findByPlateIgnoreCase(String plate);

    List<Vehicle> findByDriverProfile_Id(UUID driverProfileId);

    List<Vehicle> findByDriverProfile_IdAndStatus(UUID driverProfileId, VehicleStatus status);

    @org.springframework.data.jpa.repository.Query("SELECT v FROM Vehicle v JOIN v.driverProfile dp JOIN dp.user u WHERE u.id = :userId")
    List<Vehicle> findByUserId(@org.springframework.data.repository.query.Param("userId") UUID userId);

    @org.springframework.data.jpa.repository.Query("SELECT v FROM Vehicle v JOIN v.driverProfile dp JOIN dp.user u WHERE u.id IN :userIds")
    List<Vehicle> findByUserIds(@org.springframework.data.repository.query.Param("userIds") java.util.Collection<UUID> userIds);
}
