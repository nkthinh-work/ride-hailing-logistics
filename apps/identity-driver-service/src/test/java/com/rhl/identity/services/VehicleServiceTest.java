package com.rhl.identity.services;

import com.rhl.identity.dtos.VehicleRequest;
import com.rhl.identity.dtos.VehicleResponse;
import com.rhl.identity.entities.DriverProfile;
import com.rhl.identity.entities.User;
import com.rhl.identity.entities.Vehicle;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import com.rhl.identity.entities.enums.VehicleStatus;
import com.rhl.identity.entities.enums.VehicleType;
import com.rhl.identity.exceptions.DriverApplicationNotFoundException;
import com.rhl.identity.exceptions.VehicleNotFoundException;
import com.rhl.identity.exceptions.VehiclePlateAlreadyExistsException;
import com.rhl.identity.repositories.DriverProfileRepository;
import com.rhl.identity.repositories.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private DriverProfileRepository driverProfileRepository;

    @InjectMocks
    private VehicleService vehicleService;

    private UUID userId;
    private UUID applicationId;
    private User user;
    private DriverProfile driverProfile;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        applicationId = UUID.randomUUID();

        user = new User("driver@rhl.com", "hashed_pwd");
        ReflectionTestUtils.setField(user, "id", userId);

        driverProfile = new DriverProfile(user, "Nguyen Van A", "0901234567", "GPLX123456", "29A-11111");
        ReflectionTestUtils.setField(driverProfile, "id", applicationId);
        ReflectionTestUtils.setField(driverProfile, "status", DriverApplicationStatus.APPROVED);
    }

    @Nested
    @DisplayName("registerVehicle Tests")
    class RegisterVehicleTests {

        @Test
        @DisplayName("Successfully register vehicle for APPROVED driver profile -> status is ACTIVE")
        void registerVehicle_whenApprovedProfile_createsActiveVehicle() {
            VehicleRequest request = new VehicleRequest(VehicleType.CAR_4_SEAT, "29A-99999", "Toyota", "Vios", "White");

            when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(driverProfile));
            when(vehicleRepository.existsByPlateIgnoreCase("29A-99999")).thenReturn(false);
            when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> invocation.getArgument(0));

            VehicleResponse response = vehicleService.registerVehicle(userId, request);

            assertThat(response).isNotNull();
            assertThat(response.plate()).isEqualTo("29A-99999");
            assertThat(response.type()).isEqualTo(VehicleType.CAR_4_SEAT);
            assertThat(response.make()).isEqualTo("Toyota");
            assertThat(response.model()).isEqualTo("Vios");
            assertThat(response.color()).isEqualTo("White");
            assertThat(response.status()).isEqualTo(VehicleStatus.ACTIVE);
            assertThat(response.driverProfileId()).isEqualTo(applicationId);

            verify(vehicleRepository).save(any(Vehicle.class));
        }

        @Test
        @DisplayName("Successfully register vehicle for PENDING driver profile -> status is INACTIVE until approved")
        void registerVehicle_whenPendingProfile_createsInactiveVehicle() {
            ReflectionTestUtils.setField(driverProfile, "status", DriverApplicationStatus.PENDING);
            VehicleRequest request = new VehicleRequest(VehicleType.MOTORBIKE, "29X1-12345", "Honda", "Wave Alpha", "Black");

            when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(driverProfile));
            when(vehicleRepository.existsByPlateIgnoreCase("29X1-12345")).thenReturn(false);
            when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> invocation.getArgument(0));

            VehicleResponse response = vehicleService.registerVehicle(userId, request);

            assertThat(response.status()).isEqualTo(VehicleStatus.INACTIVE);
        }

        @Test
        @DisplayName("Registration throws DriverApplicationNotFoundException if user has no driver profile")
        void registerVehicle_whenNoProfile_throwsException() {
            VehicleRequest request = new VehicleRequest(VehicleType.CAR_4_SEAT, "29A-99999", "Toyota", "Vios", "White");

            when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> vehicleService.registerVehicle(userId, request))
                    .isInstanceOf(DriverApplicationNotFoundException.class);

            verifyNoInteractions(vehicleRepository);
        }

        @Test
        @DisplayName("Registration throws IllegalStateException if driver application was REJECTED")
        void registerVehicle_whenRejectedProfile_throwsIllegalStateException() {
            ReflectionTestUtils.setField(driverProfile, "status", DriverApplicationStatus.REJECTED);
            VehicleRequest request = new VehicleRequest(VehicleType.CAR_4_SEAT, "29A-99999", "Toyota", "Vios", "White");

            when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(driverProfile));

            assertThatThrownBy(() -> vehicleService.registerVehicle(userId, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot register vehicle for rejected driver application");

            verifyNoInteractions(vehicleRepository);
        }

        @Test
        @DisplayName("Registration throws VehiclePlateAlreadyExistsException on duplicate license plate")
        void registerVehicle_whenPlateAlreadyExists_throwsConflictException() {
            VehicleRequest request = new VehicleRequest(VehicleType.CAR_4_SEAT, "29a-99999 ", "Toyota", "Vios", "White");

            when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(driverProfile));
            when(vehicleRepository.existsByPlateIgnoreCase("29A-99999")).thenReturn(true);

            assertThatThrownBy(() -> vehicleService.registerVehicle(userId, request))
                    .isInstanceOf(VehiclePlateAlreadyExistsException.class)
                    .hasMessageContaining("29A-99999");

            verify(vehicleRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getMyVehicles Tests")
    class GetMyVehiclesTests {

        @Test
        @DisplayName("Returns list of vehicles owned by current user")
        void getMyVehicles_returnsUserVehicles() {
            Vehicle v1 = new Vehicle(driverProfile, VehicleType.CAR_4_SEAT, "29A-11111", "Toyota", "Vios", "White", VehicleStatus.ACTIVE);
            Vehicle v2 = new Vehicle(driverProfile, VehicleType.MOTORBIKE, "29X1-22222", "Honda", "Vision", "Red", VehicleStatus.INACTIVE);

            when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.of(driverProfile));
            when(vehicleRepository.findByDriverProfile_Id(applicationId)).thenReturn(List.of(v1, v2));

            List<VehicleResponse> result = vehicleService.getMyVehicles(userId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).plate()).isEqualTo("29A-11111");
            assertThat(result.get(1).plate()).isEqualTo("29X1-22222");
        }

        @Test
        @DisplayName("Throws DriverApplicationNotFoundException if no profile exists for user")
        void getMyVehicles_whenNoProfile_throwsNotFound() {
            when(driverProfileRepository.findByUser_Id(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> vehicleService.getMyVehicles(userId))
                    .isInstanceOf(DriverApplicationNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getVehiclesByDriverProfileId Tests")
    class GetVehiclesByDriverProfileIdTests {

        @Test
        @DisplayName("Reviewer fetches all vehicles for valid applicationId")
        void getVehiclesByDriverProfileId_whenFound_returnsVehicles() {
            Vehicle v1 = new Vehicle(driverProfile, VehicleType.CAR_4_SEAT, "29A-11111", "Toyota", "Vios", "White", VehicleStatus.ACTIVE);

            when(driverProfileRepository.existsById(applicationId)).thenReturn(true);
            when(vehicleRepository.findByDriverProfile_Id(applicationId)).thenReturn(List.of(v1));

            List<VehicleResponse> result = vehicleService.getVehiclesByDriverProfileId(applicationId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).plate()).isEqualTo("29A-11111");
        }

        @Test
        @DisplayName("Throws DriverApplicationNotFoundException if applicationId does not exist")
        void getVehiclesByDriverProfileId_whenNotFound_throwsNotFound() {
            when(driverProfileRepository.existsById(applicationId)).thenReturn(false);

            assertThatThrownBy(() -> vehicleService.getVehiclesByDriverProfileId(applicationId))
                    .isInstanceOf(DriverApplicationNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getVehicleById Tests (Ownership & Authorization)")
    class GetVehicleByIdTests {

        private UUID vehicleId;
        private Vehicle vehicle;

        @BeforeEach
        void initVehicle() {
            vehicleId = UUID.randomUUID();
            vehicle = new Vehicle(driverProfile, VehicleType.CAR_4_SEAT, "29A-11111", "Toyota", "Vios", "White", VehicleStatus.ACTIVE);
            ReflectionTestUtils.setField(vehicle, "id", vehicleId);
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("Owner driver can access their own vehicle details")
        void getVehicleById_whenOwner_returnsVehicle() {
            when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));

            VehicleResponse response = vehicleService.getVehicleById(userId, vehicleId);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(vehicleId);
            assertThat(response.plate()).isEqualTo("29A-11111");
        }

        @Test
        @DisplayName("Reviewer can access vehicle even if they do not own it")
        void getVehicleById_whenReviewer_returnsVehicle() {
            UUID reviewerUserId = UUID.randomUUID();
            when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));

            org.springframework.security.core.context.SecurityContext context =
                    org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "reviewer", "n/a", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("DRIVER_REVIEW"))
            ));
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);

            VehicleResponse response = vehicleService.getVehicleById(reviewerUserId, vehicleId);

            assertThat(response).isNotNull();
            assertThat(response.plate()).isEqualTo("29A-11111");

            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("Non-owner non-reviewer user is forbidden with AccessDeniedException")
        void getVehicleById_whenStranger_throwsAccessDenied() {
            UUID strangerId = UUID.randomUUID();
            when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));

            assertThatThrownBy(() -> vehicleService.getVehicleById(strangerId, vehicleId))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("You do not own this vehicle");
        }

        @Test
        @DisplayName("Throws VehicleNotFoundException if vehicleId does not exist")
        void getVehicleById_whenVehicleNotFound_throwsNotFound() {
            when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> vehicleService.getVehicleById(userId, vehicleId))
                    .isInstanceOf(VehicleNotFoundException.class);
        }
    }
}
