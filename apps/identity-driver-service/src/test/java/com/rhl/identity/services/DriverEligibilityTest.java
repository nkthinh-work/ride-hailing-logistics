package com.rhl.identity.services;

import com.rhl.identity.dtos.DriverEligibilityResponse;
import com.rhl.identity.entities.DriverProfile;
import com.rhl.identity.entities.Role;
import com.rhl.identity.entities.User;
import com.rhl.identity.entities.Vehicle;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import com.rhl.identity.entities.enums.VehicleStatus;
import com.rhl.identity.entities.enums.VehicleType;
import com.rhl.identity.exceptions.DriverApplicationNotFoundException;
import com.rhl.identity.repositories.DriverAuditLogRepository;
import com.rhl.identity.repositories.DriverProfileRepository;
import com.rhl.identity.repositories.RoleRepository;
import com.rhl.identity.repositories.UserRepository;
import com.rhl.identity.repositories.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverEligibilityTest {

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

    private UUID userId;
    private UUID applicationId;
    private User activeUser;
    private Role driverRole;
    private Role customerRole;
    private Vehicle activeVehicle;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        applicationId = UUID.randomUUID();

        activeUser = new User("driver@rhl.com", "hashed_pwd");
        ReflectionTestUtils.setField(activeUser, "id", userId);

        driverRole = createRole("DRIVER", "Driver");
        customerRole = createRole("CUSTOMER", "Customer");

        activeVehicle = new Vehicle(null, VehicleType.CAR_4_SEAT, "29A-12345", "Toyota", "Vios", "White", VehicleStatus.ACTIVE);
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

    private DriverProfile createProfile(User user, DriverApplicationStatus status) {
        DriverProfile profile = new DriverProfile(user, "Nguyen Van B", "0912345678", "GPLX9999", "30F-99999");
        ReflectionTestUtils.setField(profile, "id", applicationId);
        ReflectionTestUtils.setField(profile, "status", status);
        return profile;
    }

    @Test
    @DisplayName("Eligible driver: ACTIVE account + DRIVER role + APPROVED profile + ACTIVE vehicle -> returns eligible=true")
    void checkEligibility_whenAllConditionsMet_returnsEligibleTrue() {
        activeUser.addRole(driverRole);
        DriverProfile approvedProfile = createProfile(activeUser, DriverApplicationStatus.APPROVED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(approvedProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(applicationId, VehicleStatus.ACTIVE))
                .thenReturn(List.of(activeVehicle));

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(userId);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.eligible()).isTrue();
        assertThat(response.accountStatus()).isEqualTo("ACTIVE");
        assertThat(response.applicationStatus()).isEqualTo(DriverApplicationStatus.APPROVED);
        assertThat(response.hasDriverRole()).isTrue();
        assertThat(response.reasons()).isEmpty();
    }

    @Test
    @DisplayName("Ineligible: Inactive account status -> returns eligible=false with ACCOUNT_INACTIVE")
    void checkEligibility_whenUserSuspended_returnsEligibleFalseWithAccountInactive() {
        ReflectionTestUtils.setField(activeUser, "status", "SUSPENDED");
        activeUser.addRole(driverRole);
        DriverProfile approvedProfile = createProfile(activeUser, DriverApplicationStatus.APPROVED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(approvedProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(applicationId, VehicleStatus.ACTIVE))
                .thenReturn(List.of(activeVehicle));

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(userId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.accountStatus()).isEqualTo("SUSPENDED");
        assertThat(response.reasons()).contains("ACCOUNT_INACTIVE");
    }

    @Test
    @DisplayName("Ineligible: Driver role missing from user -> returns eligible=false with DRIVER_ROLE_MISSING")
    void checkEligibility_whenDriverRoleMissing_returnsEligibleFalseWithDriverRoleMissing() {
        activeUser.addRole(customerRole);
        DriverProfile approvedProfile = createProfile(activeUser, DriverApplicationStatus.APPROVED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(approvedProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(applicationId, VehicleStatus.ACTIVE))
                .thenReturn(List.of(activeVehicle));

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(userId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.hasDriverRole()).isFalse();
        assertThat(response.reasons()).contains("DRIVER_ROLE_MISSING");
    }

    @Test
    @DisplayName("Ineligible: Application still PENDING -> returns eligible=false with APPLICATION_NOT_APPROVED")
    void checkEligibility_whenApplicationPending_returnsEligibleFalseWithAppPending() {
        activeUser.addRole(driverRole);
        DriverProfile pendingProfile = createProfile(activeUser, DriverApplicationStatus.PENDING);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(pendingProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(applicationId, VehicleStatus.ACTIVE))
                .thenReturn(List.of(activeVehicle));

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(userId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.applicationStatus()).isEqualTo(DriverApplicationStatus.PENDING);
        assertThat(response.reasons()).contains("APPLICATION_NOT_APPROVED");
    }

    @Test
    @DisplayName("Ineligible: Application REJECTED -> returns eligible=false with APPLICATION_NOT_APPROVED")
    void checkEligibility_whenApplicationRejected_returnsEligibleFalseWithAppRejected() {
        activeUser.addRole(customerRole);
        DriverProfile rejectedProfile = createProfile(activeUser, DriverApplicationStatus.REJECTED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(rejectedProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(applicationId, VehicleStatus.ACTIVE))
                .thenReturn(List.of(activeVehicle));

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(userId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.applicationStatus()).isEqualTo(DriverApplicationStatus.REJECTED);
        assertThat(response.reasons()).contains("APPLICATION_NOT_APPROVED");
    }

    @Test
    @DisplayName("Ineligible: No active vehicle -> returns eligible=false with NO_ACTIVE_VEHICLE")
    void checkEligibility_whenNoActiveVehicle_returnsEligibleFalseWithNoActiveVehicle() {
        activeUser.addRole(driverRole);
        DriverProfile approvedProfile = createProfile(activeUser, DriverApplicationStatus.APPROVED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(approvedProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(applicationId, VehicleStatus.ACTIVE))
                .thenReturn(List.of());

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(userId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.reasons()).contains("NO_ACTIVE_VEHICLE");
    }

    @Test
    @DisplayName("Ineligible: No profile found -> returns eligible=false with PROFILE_NOT_FOUND")
    void checkEligibility_whenNoProfileFound_returnsEligibleFalseWithProfileNotFound() {
        activeUser.addRole(customerRole);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.empty());

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(userId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.applicationStatus()).isNull();
        assertThat(response.reasons()).contains("PROFILE_NOT_FOUND", "DRIVER_ROLE_MISSING");
    }

    @Test
    @DisplayName("getEligibilityByApplicationId: Non-existent application throws DriverApplicationNotFoundException")
    void getEligibilityByApplicationId_whenNotFound_throwsNotFoundException() {
        when(driverProfileRepository.findById(applicationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> driverService.getEligibilityByApplicationId(applicationId))
                .isInstanceOf(DriverApplicationNotFoundException.class);
    }

    @Test
    @DisplayName("getEligibilityByApplicationId: Existing application delegates and returns correct eligibility")
    void getEligibilityByApplicationId_whenFound_delegatesCorrectly() {
        activeUser.addRole(driverRole);
        DriverProfile profile = createProfile(activeUser, DriverApplicationStatus.APPROVED);

        when(driverProfileRepository.findById(applicationId)).thenReturn(Optional.of(profile));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(profile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(applicationId, VehicleStatus.ACTIVE))
                .thenReturn(List.of(activeVehicle));

        DriverEligibilityResponse response = driverService.getEligibilityByApplicationId(applicationId);

        assertThat(response.eligible()).isTrue();
        assertThat(response.userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("Ineligible: Profile is SUSPENDED -> returns eligible=false with DRIVER_SUSPENDED and APPLICATION_NOT_APPROVED")
    void checkEligibility_whenDriverSuspended_returnsEligibleFalseWithDriverSuspended() {
        activeUser.addRole(driverRole);
        DriverProfile suspendedProfile = createProfile(activeUser, DriverApplicationStatus.SUSPENDED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(suspendedProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(applicationId, VehicleStatus.ACTIVE))
                .thenReturn(List.of(activeVehicle));

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(userId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.applicationStatus()).isEqualTo(DriverApplicationStatus.SUSPENDED);
        assertThat(response.reasons()).contains("DRIVER_SUSPENDED", "APPLICATION_NOT_APPROVED");
    }

    @Test
    @DisplayName("Ineligible: Vehicle remains ACTIVE while profile is SUSPENDED -> still returns eligible=false")
    void checkEligibility_vehicleStillActiveWhenDriverSuspended_remainsIneligible() {
        activeUser.addRole(driverRole);
        DriverProfile suspendedProfile = createProfile(activeUser, DriverApplicationStatus.SUSPENDED);

        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(suspendedProfile));
        when(vehicleRepository.findByDriverProfile_IdAndStatus(applicationId, VehicleStatus.ACTIVE))
                .thenReturn(List.of(activeVehicle));

        DriverEligibilityResponse response = driverService.getEligibilityByUserId(userId);

        assertThat(response.eligible()).isFalse();
        assertThat(response.reasons()).doesNotContain("NO_ACTIVE_VEHICLE");
        assertThat(response.reasons()).contains("DRIVER_SUSPENDED");
    }
}
