package com.rhl.identity.services;

import com.rhl.identity.dtos.VehicleRequest;
import com.rhl.identity.dtos.VehicleResponse;
import com.rhl.identity.entities.DriverProfile;
import com.rhl.identity.entities.Vehicle;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import com.rhl.identity.entities.enums.VehicleStatus;
import com.rhl.identity.exceptions.DriverApplicationNotFoundException;
import com.rhl.identity.exceptions.VehicleNotFoundException;
import com.rhl.identity.exceptions.VehiclePlateAlreadyExistsException;
import com.rhl.identity.repositories.DriverProfileRepository;
import com.rhl.identity.repositories.VehicleRepository;
import com.rhl.identity.services.interfaces.IVehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VehicleService implements IVehicleService {

    private final VehicleRepository vehicleRepository;
    private final DriverProfileRepository driverProfileRepository;

    @Override
    @Transactional
    public VehicleResponse registerVehicle(UUID userId, VehicleRequest request) {

        DriverProfile profile = driverProfileRepository.findByUser_Id(userId)
                .orElseThrow(DriverApplicationNotFoundException::new);

        if (profile.getStatus() == DriverApplicationStatus.REJECTED) {
            throw new IllegalStateException("Cannot register vehicle for rejected driver application");
        }

        String normalizedPlate = request.plate().trim().toUpperCase(Locale.ROOT);

        if (vehicleRepository.existsByPlateIgnoreCase(normalizedPlate)) {
            throw new VehiclePlateAlreadyExistsException(normalizedPlate);
        }

        VehicleStatus initialStatus = (profile.getStatus() == DriverApplicationStatus.APPROVED)
                ? VehicleStatus.ACTIVE
                : VehicleStatus.INACTIVE;

        Vehicle vehicle = new Vehicle(
                profile,
                request.type(),
                normalizedPlate,
                request.make().trim(),
                request.model().trim(),
                request.color().trim(),
                initialStatus
        );

        vehicleRepository.save(vehicle);
        profile.addVehicle(vehicle);

        return toResponse(vehicle);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VehicleResponse> getMyVehicles(UUID userId) {

        DriverProfile profile = driverProfileRepository.findByUser_Id(userId)
                .orElseThrow(DriverApplicationNotFoundException::new);

        return vehicleRepository.findByDriverProfile_Id(profile.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @Transactional(readOnly = true)
    public List<VehicleResponse> getVehiclesByDriverProfileId(UUID driverProfileId) {

        if (!driverProfileRepository.existsById(driverProfileId)) {
            throw new DriverApplicationNotFoundException();
        }

        return vehicleRepository.findByDriverProfile_Id(driverProfileId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VehicleResponse getVehicleById(UUID userId, UUID vehicleId) {

        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(VehicleNotFoundException::new);

        boolean isOwner = vehicle.getDriverProfile().getUser().getId().equals(userId);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean hasReviewerRole = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "DRIVER_REVIEW".equals(a.getAuthority()));

        if (!isOwner && !hasReviewerRole) {
            throw new AccessDeniedException("You do not own this vehicle");
        }

        return toResponse(vehicle);
    }

    public VehicleResponse toResponse(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getDriverProfile().getId(),
                vehicle.getType(),
                vehicle.getPlate(),
                vehicle.getMake(),
                vehicle.getModel(),
                vehicle.getColor(),
                vehicle.getStatus(),
                vehicle.getCreatedAt()
        );
    }
}
