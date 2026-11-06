package com.rhl.identity.services;

import com.rhl.identity.dtos.DriverApplicationRequest;
import com.rhl.identity.dtos.DriverApplicationResponse;
import com.rhl.identity.dtos.DriverEligibilityResponse;
import com.rhl.identity.entities.DriverProfile;
import com.rhl.identity.entities.Role;
import com.rhl.identity.entities.User;
import com.rhl.identity.entities.Vehicle;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import com.rhl.identity.entities.enums.VehicleStatus;
import com.rhl.identity.entities.enums.VehicleType;
import com.rhl.identity.exceptions.DriverApplicationConflictException;
import com.rhl.identity.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DriverOnboardingDeadlockTest {

    @Mock
    private DriverProfileRepository driverProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private DriverAuditLogRepository driverAuditLogRepository;

    @InjectMocks
    private DriverService driverService;

    private UUID customerUserId;
    private UUID driverUserId;
    private UUID reviewerUserId;
    private User customerUser;
    private User driverUser;
    private User reviewerUser;
    private Role customerRole;
    private Role driverRole;
    private DriverApplicationRequest sampleRequest;

    @BeforeEach
    void setUp() {
        customerUserId = UUID.randomUUID();
        driverUserId = UUID.randomUUID();
        reviewerUserId = UUID.randomUUID();

        customerRole = createRole("CUSTOMER", "Customer");
        driverRole = createRole("DRIVER", "Driver");

        customerUser = new User("customer@example.com", "hashed_pwd");
        ReflectionTestUtils.setField(customerUser, "id", customerUserId);
        ReflectionTestUtils.setField(customerUser, "status", "ACTIVE");
        customerUser.addRole(customerRole);

        driverUser = new User("driver@gmail.com", "hashed_pwd");
        ReflectionTestUtils.setField(driverUser, "id", driverUserId);
        ReflectionTestUtils.setField(driverUser, "status", "ACTIVE");
        driverUser.addRole(driverRole);

        reviewerUser = new User("reviewer@example.com", "hashed_pwd");
        ReflectionTestUtils.setField(reviewerUser, "id", reviewerUserId);
        ReflectionTestUtils.setField(reviewerUser, "status", "ACTIVE");

        sampleRequest = new DriverApplicationRequest(
                "Nguyen Van Driver",
                "0987654321",
                "B2-12345678",
                "29B-999.99"
        );
    }

    private Role createRole(String code, String name) {
        try {
            var ctor = Role.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Role role = ctor.newInstance();
            ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(role, "code", code);
            ReflectionTestUtils.setField(role, "name", name);
            return role;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("1. CUSTOMER without profile applies -> 201, PENDING profile, INACTIVE vehicle")
    void apply_customerWithoutProfile_createsPendingProfileAndInactiveVehicle() {
        when(driverProfileRepository.existsByUser_Id(customerUserId)).thenReturn(false);
        when(userRepository.findById(customerUserId)).thenReturn(Optional.of(customerUser));

        DriverApplicationResponse response = driverService.apply(customerUserId, sampleRequest);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(DriverApplicationStatus.PENDING);
        assertThat(response.fullName()).isEqualTo("Nguyen Van Driver");
        assertThat(response.vehiclePlate()).isEqualTo("29B-999.99");

        ArgumentCaptor<DriverProfile> profileCaptor = ArgumentCaptor.forClass(DriverProfile.class);
        verify(driverProfileRepository).save(profileCaptor.capture());
        DriverProfile savedProfile = profileCaptor.getValue();
        assertThat(savedProfile.getStatus()).isEqualTo(DriverApplicationStatus.PENDING);
        assertThat(savedProfile.getUser().getId()).isEqualTo(customerUserId);

        ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleRepository).save(vehicleCaptor.capture());
        Vehicle savedVehicle = vehicleCaptor.getValue();
        assertThat(savedVehicle.getStatus()).isEqualTo(VehicleStatus.INACTIVE);
        assertThat(savedVehicle.getPlate()).isEqualTo("29B-999.99");
    }

    @Test
    @DisplayName("2. Existing DRIVER role without profile applies -> 201, PENDING profile, INACTIVE vehicle (Deadlock resolved!)")
    void apply_existingDriverRoleWithoutProfile_createsPendingProfileAndInactiveVehicle() {
        when(driverProfileRepository.existsByUser_Id(driverUserId)).thenReturn(false);
        when(userRepository.findById(driverUserId)).thenReturn(Optional.of(driverUser));

        // Must NOT throw "You are already a driver"
        DriverApplicationResponse response = driverService.apply(driverUserId, sampleRequest);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(DriverApplicationStatus.PENDING);
        assertThat(response.fullName()).isEqualTo("Nguyen Van Driver");

        ArgumentCaptor<DriverProfile> profileCaptor = ArgumentCaptor.forClass(DriverProfile.class);
        verify(driverProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getStatus()).isEqualTo(DriverApplicationStatus.PENDING);

        ArgumentCaptor<Vehicle> vehicleCaptor = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleRepository).save(vehicleCaptor.capture());
        assertThat(vehicleCaptor.getValue().getStatus()).isEqualTo(VehicleStatus.INACTIVE);
    }

    @Test
    @DisplayName("3. User with existing profile applies again -> throws DriverApplicationConflictException (409)")
    void apply_userWithExistingProfile_throwsDriverApplicationConflictException() {
        when(driverProfileRepository.existsByUser_Id(customerUserId)).thenReturn(true);

        assertThatThrownBy(() -> driverService.apply(customerUserId, sampleRequest))
                .isInstanceOf(DriverApplicationConflictException.class)
                .hasMessageContaining("You already have a driver application");

        verify(driverProfileRepository, never()).save(any());
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    @DisplayName("4. PENDING profile -> getEligibilityByUserId returns eligible=false with APPLICATION_NOT_APPROVED")
    void getEligibility_pendingProfile_returnsIneligible() {
        DriverProfile pendingProfile = new DriverProfile(
                customerUser,
                "Nguyen Van A",
                "0912345678",
                "GPLX1111",
                "29A-111.11"
        );
        UUID appId = UUID.randomUUID();
        ReflectionTestUtils.setField(pendingProfile, "id", appId);
        ReflectionTestUtils.setField(pendingProfile, "status", DriverApplicationStatus.PENDING);

        when(userRepository.findById(customerUserId)).thenReturn(Optional.of(customerUser));
        when(driverProfileRepository.findByUser_Id(customerUserId)).thenReturn(Optional.of(pendingProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(appId, VehicleStatus.ACTIVE)).thenReturn(List.of());

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(customerUserId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.reasons()).contains("APPLICATION_NOT_APPROVED");
        assertThat(response.reasons()).contains("NO_ACTIVE_VEHICLE");
    }

    @Test
    @DisplayName("5. Missing profile with DRIVER role -> returns eligible=false with PROFILE_NOT_FOUND")
    void getEligibility_missingProfileWithDriverRole_returnsIneligibleWithProfileNotFound() {
        when(userRepository.findById(driverUserId)).thenReturn(Optional.of(driverUser));
        when(driverProfileRepository.findByUser_Id(driverUserId)).thenReturn(Optional.empty());

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(driverUserId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.hasDriverRole()).isTrue();
        assertThat(response.reasons()).contains("PROFILE_NOT_FOUND");
    }

    @Test
    @DisplayName("6. APPROVED profile + DRIVER role + ACTIVE vehicle -> returns eligible=true")
    void getEligibility_approvedProfileWithDriverRoleAndActiveVehicle_returnsEligibleTrue() {
        DriverProfile approvedProfile = new DriverProfile(
                driverUser,
                "Nguyen Van Driver",
                "0987654321",
                "B2-12345678",
                "29B-999.99"
        );
        UUID appId = UUID.randomUUID();
        ReflectionTestUtils.setField(approvedProfile, "id", appId);
        ReflectionTestUtils.setField(approvedProfile, "status", DriverApplicationStatus.APPROVED);

        Vehicle activeVehicle = new Vehicle(
                approvedProfile,
                VehicleType.MOTORBIKE,
                "29B-999.99",
                "Honda",
                "Wave",
                "Black",
                VehicleStatus.ACTIVE
        );

        when(userRepository.findById(driverUserId)).thenReturn(Optional.of(driverUser));
        when(driverProfileRepository.findByUser_Id(driverUserId)).thenReturn(Optional.of(approvedProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(appId, VehicleStatus.ACTIVE))
                .thenReturn(List.of(activeVehicle));

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(driverUserId);

        assertThat(response.eligible()).isTrue();
        assertThat(response.reasons()).isEmpty();
    }

    @Test
    @DisplayName("7. Reviewer approval -> correct profile APPROVED and vehicle ACTIVE transitions")
    void approve_byReviewer_transitionsProfileToApprovedAndVehicleToActive() {
        UUID appId = UUID.randomUUID();
        DriverProfile pendingProfile = new DriverProfile(
                customerUser,
                "Nguyen Van Customer",
                "0912345678",
                "GPLX1111",
                "29A-111.11"
        );
        ReflectionTestUtils.setField(pendingProfile, "id", appId);
        ReflectionTestUtils.setField(pendingProfile, "status", DriverApplicationStatus.PENDING);

        Vehicle inactiveVehicle = new Vehicle(
                pendingProfile,
                VehicleType.MOTORBIKE,
                "29A-111.11",
                "Standard",
                "Standard",
                "Black",
                VehicleStatus.INACTIVE
        );

        when(driverProfileRepository.findByIdForUpdate(appId)).thenReturn(Optional.of(pendingProfile));
        when(userRepository.findById(reviewerUserId)).thenReturn(Optional.of(reviewerUser));
        when(roleRepository.findByCode("DRIVER")).thenReturn(Optional.of(driverRole));
        when(vehicleRepository.findByDriverProfile_Id(appId)).thenReturn(List.of(inactiveVehicle));

        DriverApplicationResponse response = driverService.approve(reviewerUserId, appId);

        assertThat(response.status()).isEqualTo(DriverApplicationStatus.APPROVED);
        assertThat(pendingProfile.getStatus()).isEqualTo(DriverApplicationStatus.APPROVED);
        assertThat(inactiveVehicle.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(customerUser.getRoles()).extracting(Role::getCode).contains("DRIVER");

        verify(driverAuditLogRepository).save(any());
    }
}
