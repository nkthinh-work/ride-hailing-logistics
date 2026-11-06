
package com.rhl.identity.services;

import com.rhl.identity.dtos.*;
import com.rhl.identity.entities.*;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import com.rhl.identity.entities.enums.DriverAuditAction;
import com.rhl.identity.exceptions.*;
import com.rhl.identity.repositories.*;

import com.rhl.identity.services.interfaces.IDriverService;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rhl.identity.entities.enums.VehicleStatus;
import com.rhl.identity.entities.enums.VehicleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DriverService implements IDriverService {

    private final DriverProfileRepository driverProfileRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverAuditLogRepository driverAuditLogRepository;

    @Transactional
    public DriverApplicationResponse apply(
            UUID userId,
            DriverApplicationRequest request) {

        if (driverProfileRepository.existsByUser_Id(userId)) {
            throw new DriverApplicationConflictException(
                    "You already have a driver application"
            );
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalStateException("User not found")
                );

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException("User account is not active");
        }

        DriverProfile profile = new DriverProfile(
                user,
                request.fullName().trim(),
                request.phone().trim(),
                request.driverLicenseNumber().trim(),
                request.vehiclePlate().trim()
        );

        driverProfileRepository.save(profile);

        String normalizedPlate = request.vehiclePlate().trim().toUpperCase(Locale.ROOT);
        Vehicle vehicle = new Vehicle(
                profile,
                VehicleType.MOTORBIKE,
                normalizedPlate,
                "Standard",
                "Standard",
                "Black",
                VehicleStatus.INACTIVE
        );
        vehicleRepository.save(vehicle);
        profile.addVehicle(vehicle);

        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public DriverApplicationResponse getMyApplication(UUID userId) {

        DriverProfile profile = driverProfileRepository
                .findByUser_Id(userId)
                .orElseThrow(DriverApplicationNotFoundException::new);

        return toResponse(profile);
    }


    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @Transactional(readOnly = true)
    public List<DriverApplicationResponse> listApplications(
            DriverApplicationStatus status) {

        return driverProfileRepository
                .findByStatus(status)
                .stream()
                .map(this::toResponse)
                .toList();
    }


    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @Transactional
    public DriverApplicationResponse approve(
            UUID reviewerId,
            UUID applicationId) {

        DriverProfile profile = driverProfileRepository
                .findByIdForUpdate(applicationId)
                .orElseThrow(DriverApplicationNotFoundException::new);

        if (profile.getStatus() != DriverApplicationStatus.PENDING) {
            throw new DriverApplicationConflictException(
                    "Application has already been reviewed"
            );
        }

        User reviewer = userRepository.findById(reviewerId)
                .orElseThrow(() ->
                        new IllegalStateException("Reviewer not found")
                );

        Role driverRole = roleRepository.findByCode("DRIVER")
                .orElseThrow(() ->
                        new IllegalStateException(
                                "DRIVER role not configured"
                        )
                );

        profile.approve(reviewer);

        profile.getUser().addRole(driverRole);

        List<Vehicle> vehicles = vehicleRepository.findByDriverProfile_Id(profile.getId());
        for (Vehicle v : vehicles) {
            v.updateStatus(VehicleStatus.ACTIVE);
        }

        driverAuditLogRepository.save(new DriverAuditLog(
                profile,
                reviewer,
                DriverAuditAction.APPROVE,
                DriverApplicationStatus.PENDING,
                DriverApplicationStatus.APPROVED,
                null
        ));

        return toResponse(profile);
    }

    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @Transactional
    public DriverApplicationResponse reject(
            UUID reviewerId,
            UUID applicationId,
            RejectDriverRequest request) {

        DriverProfile profile = driverProfileRepository
                .findByIdForUpdate(applicationId)
                .orElseThrow(DriverApplicationNotFoundException::new);

        if (profile.getStatus() != DriverApplicationStatus.PENDING) {
            throw new DriverApplicationConflictException(
                    "Application has already been reviewed"
            );
        }

        User reviewer = userRepository.findById(reviewerId)
                .orElseThrow(() ->
                        new IllegalStateException("Reviewer not found")
                );

        profile.reject(reviewer, request.reason().trim());

        driverAuditLogRepository.save(new DriverAuditLog(
                profile,
                reviewer,
                DriverAuditAction.REJECT,
                DriverApplicationStatus.PENDING,
                DriverApplicationStatus.REJECTED,
                request.reason().trim()
        ));

        return toResponse(profile);
    }

    @PreAuthorize("hasAuthority('DRIVER_SUSPEND') or hasRole('ADMINISTRATOR')")
    @Transactional
    public DriverApplicationResponse suspend(
            UUID actorId,
            UUID applicationId,
            SuspendDriverRequest request) {

        DriverProfile profile = driverProfileRepository
                .findByIdForUpdate(applicationId)
                .orElseThrow(DriverApplicationNotFoundException::new);

        if (profile.getStatus() != DriverApplicationStatus.APPROVED) {
            throw new DriverApplicationConflictException(
                    "Only APPROVED driver profiles can be suspended. Current status: " + profile.getStatus()
            );
        }

        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));

        profile.suspend();

        driverAuditLogRepository.save(new DriverAuditLog(
                profile,
                actor,
                DriverAuditAction.SUSPEND,
                DriverApplicationStatus.APPROVED,
                DriverApplicationStatus.SUSPENDED,
                request.reason().trim()
        ));

        return toResponse(profile);
    }

    @PreAuthorize("hasAuthority('DRIVER_SUSPEND') or hasRole('ADMINISTRATOR')")
    @Transactional
    public DriverApplicationResponse reactivate(
            UUID actorId,
            UUID applicationId) {

        DriverProfile profile = driverProfileRepository
                .findByIdForUpdate(applicationId)
                .orElseThrow(DriverApplicationNotFoundException::new);

        if (profile.getStatus() != DriverApplicationStatus.SUSPENDED) {
            throw new DriverApplicationConflictException(
                    "Only SUSPENDED driver profiles can be reactivated. Current status: " + profile.getStatus()
            );
        }

        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));

        profile.reactivate();

        driverAuditLogRepository.save(new DriverAuditLog(
                profile,
                actor,
                DriverAuditAction.REACTIVATE,
                DriverApplicationStatus.SUSPENDED,
                DriverApplicationStatus.APPROVED,
                "Driver reactivated by administrator"
        ));

        return toResponse(profile);
    }

    @PreAuthorize("hasAuthority('DRIVER_REVIEW') or hasAuthority('DRIVER_SUSPEND') or hasRole('ADMINISTRATOR')")
    @Transactional(readOnly = true)
    public List<DriverAuditLogResponse> getAuditLogs(UUID applicationId) {

        if (!driverProfileRepository.existsById(applicationId)) {
            throw new DriverApplicationNotFoundException();
        }

        return driverAuditLogRepository
                .findByDriverProfile_IdOrderByCreatedAtDesc(applicationId)
                .stream()
                .map(log -> new DriverAuditLogResponse(
                        log.getId(),
                        log.getDriverProfile().getId(),
                        log.getActor().getId(),
                        log.getAction(),
                        log.getPreviousStatus(),
                        log.getNewStatus(),
                        log.getReason(),
                        log.getCreatedAt()
                ))
                .toList();
    }

    public DriverApplicationResponse toResponse(
            DriverProfile profile) {

        return new DriverApplicationResponse(
                profile.getId(),
                profile.getUser().getId(),
                profile.getFullName(),
                profile.getPhone(),
                profile.getDriverLicenseNumber(),
                profile.getVehiclePlate(),
                profile.getStatus(),
                profile.getRejectionReason(),
                profile.getCreatedAt(),
                profile.getReviewedAt()
        );
    }

    @Transactional(readOnly = true)
    public DriverEligibilityResponse getEligibilityByUserId(UUID userId) {

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return new DriverEligibilityResponse(
                    userId,
                    false,
                    "NOT_FOUND",
                    null,
                    false,
                    List.of("USER_NOT_FOUND")
            );
        }

        User user = userOpt.get();

        boolean accountActive = "ACTIVE".equalsIgnoreCase(user.getStatus());
        boolean hasDriverRole = user.getRoles().stream()
                .anyMatch(role -> "DRIVER".equalsIgnoreCase(role.getCode()));

        Optional<DriverProfile> profileOpt = driverProfileRepository.findByUser_Id(userId);
        DriverApplicationStatus appStatus = profileOpt.map(DriverProfile::getStatus).orElse(null);

        List<String> reasons = new ArrayList<>();
        if (!accountActive) {
            reasons.add("ACCOUNT_INACTIVE");
        }
        if (!hasDriverRole) {
            reasons.add("DRIVER_ROLE_MISSING");
        }
        if (profileOpt.isEmpty()) {
            reasons.add("PROFILE_NOT_FOUND");
        } else if (appStatus != DriverApplicationStatus.APPROVED) {
            reasons.add("APPLICATION_NOT_APPROVED");
            if (appStatus == DriverApplicationStatus.SUSPENDED) {
                reasons.add("DRIVER_SUSPENDED");
            }
        }

        boolean hasActiveVehicle = profileOpt.isPresent() &&
                !vehicleRepository.findByDriverProfile_IdAndStatus(profileOpt.get().getId(), VehicleStatus.ACTIVE).isEmpty();

        if (profileOpt.isPresent() && !hasActiveVehicle) {
            reasons.add("NO_ACTIVE_VEHICLE");
        }

        boolean eligible = accountActive && hasDriverRole && (appStatus == DriverApplicationStatus.APPROVED) && hasActiveVehicle;

        return new DriverEligibilityResponse(
                userId,
                eligible,
                user.getStatus(),
                appStatus,
                hasDriverRole,
                reasons
        );
    }

    @PreAuthorize("hasAuthority('DRIVER_REVIEW')")
    @Transactional(readOnly = true)
    public DriverEligibilityResponse getEligibilityByApplicationId(UUID applicationId) {

        DriverProfile profile = driverProfileRepository.findById(applicationId)
                .orElseThrow(DriverApplicationNotFoundException::new);

        return getEligibilityByUserId(profile.getUser().getId());
    }

    @Transactional(readOnly = true)
    public List<DriverEligibilityResponse> getBatchEligibility(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userIds.stream()
                .distinct()
                .map(this::getEligibilityByUserId)
                .toList();
    }
}

