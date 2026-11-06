package com.rhl.identity.services;

import com.rhl.identity.dtos.DriverApplicationResponse;
import com.rhl.identity.dtos.DriverAuditLogResponse;
import com.rhl.identity.dtos.RejectDriverRequest;
import com.rhl.identity.dtos.SuspendDriverRequest;
import com.rhl.identity.entities.DriverAuditLog;
import com.rhl.identity.entities.DriverProfile;
import com.rhl.identity.entities.Role;
import com.rhl.identity.entities.User;
import com.rhl.identity.entities.Vehicle;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import com.rhl.identity.entities.enums.DriverAuditAction;
import com.rhl.identity.entities.enums.VehicleStatus;
import com.rhl.identity.entities.enums.VehicleType;
import com.rhl.identity.exceptions.DriverApplicationConflictException;
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
class DriverSuspensionServiceTest {

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

    private UUID applicationId;
    private UUID driverUserId;
    private UUID adminUserId;
    private UUID reviewerUserId;
    private User driverUser;
    private User adminUser;
    private User reviewerUser;
    private DriverProfile profile;
    private Vehicle activeVehicle;

    @BeforeEach
    void setUp() {
        applicationId = UUID.randomUUID();
        driverUserId = UUID.randomUUID();
        adminUserId = UUID.randomUUID();
        reviewerUserId = UUID.randomUUID();

        driverUser = new User("driver@rhl.com", "hash1");
        ReflectionTestUtils.setField(driverUser, "id", driverUserId);

        adminUser = new User("admin@rhl.com", "hash2");
        ReflectionTestUtils.setField(adminUser, "id", adminUserId);

        reviewerUser = new User("reviewer@rhl.com", "hash3");
        ReflectionTestUtils.setField(reviewerUser, "id", reviewerUserId);

        profile = new DriverProfile(driverUser, "Tran Van C", "0987654321", "GPLX8888", "30H-88888");
        ReflectionTestUtils.setField(profile, "id", applicationId);

        activeVehicle = new Vehicle(profile, VehicleType.CAR_4_SEAT, "30H-88888", "Honda", "City", "Silver", VehicleStatus.ACTIVE);
    }

    @Test
    @DisplayName("Suspend APPROVED driver: successfully transitions to SUSPENDED and records audit log")
    void suspend_whenApproved_updatesStatusToSuspendedAndRecordsAuditLog() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.APPROVED);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));
        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(adminUser));

        SuspendDriverRequest request = new SuspendDriverRequest("Violated safety guidelines");
        DriverApplicationResponse response = driverService.suspend(adminUserId, applicationId, request);

        assertThat(response.status()).isEqualTo(DriverApplicationStatus.SUSPENDED);
        assertThat(profile.getStatus()).isEqualTo(DriverApplicationStatus.SUSPENDED);

        ArgumentCaptor<DriverAuditLog> auditCaptor = ArgumentCaptor.forClass(DriverAuditLog.class);
        verify(driverAuditLogRepository, times(1)).save(auditCaptor.capture());

        DriverAuditLog savedLog = auditCaptor.getValue();
        assertThat(savedLog.getAction()).isEqualTo(DriverAuditAction.SUSPEND);
        assertThat(savedLog.getPreviousStatus()).isEqualTo(DriverApplicationStatus.APPROVED);
        assertThat(savedLog.getNewStatus()).isEqualTo(DriverApplicationStatus.SUSPENDED);
        assertThat(savedLog.getReason()).isEqualTo("Violated safety guidelines");
        assertThat(savedLog.getActor()).isEqualTo(adminUser);
        assertThat(savedLog.getDriverProfile()).isEqualTo(profile);
    }

    @Test
    @DisplayName("Suspend PENDING driver: throws DriverApplicationConflictException and does NOT create audit record")
    void suspend_whenPending_throwsConflictExceptionAndNoAuditRecorded() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.PENDING);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));

        SuspendDriverRequest request = new SuspendDriverRequest("Premature suspension");
        assertThatThrownBy(() -> driverService.suspend(adminUserId, applicationId, request))
                .isInstanceOf(DriverApplicationConflictException.class)
                .hasMessageContaining("Only APPROVED driver profiles can be suspended");

        assertThat(profile.getStatus()).isEqualTo(DriverApplicationStatus.PENDING);
        verifyNoInteractions(driverAuditLogRepository);
    }

    @Test
    @DisplayName("Suspend REJECTED driver: throws DriverApplicationConflictException")
    void suspend_whenRejected_throwsConflictException() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.REJECTED);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));

        SuspendDriverRequest request = new SuspendDriverRequest("Already rejected");
        assertThatThrownBy(() -> driverService.suspend(adminUserId, applicationId, request))
                .isInstanceOf(DriverApplicationConflictException.class);

        verifyNoInteractions(driverAuditLogRepository);
    }

    @Test
    @DisplayName("Suspend already SUSPENDED driver: throws DriverApplicationConflictException")
    void suspend_whenAlreadySuspended_throwsConflictException() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.SUSPENDED);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));

        SuspendDriverRequest request = new SuspendDriverRequest("Double suspension");
        assertThatThrownBy(() -> driverService.suspend(adminUserId, applicationId, request))
                .isInstanceOf(DriverApplicationConflictException.class);

        verifyNoInteractions(driverAuditLogRepository);
    }

    @Test
    @DisplayName("Suspend non-existent application: throws DriverApplicationNotFoundException")
    void suspend_whenNotFound_throwsNotFoundException() {
        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.empty());

        SuspendDriverRequest request = new SuspendDriverRequest("Ghost application");
        assertThatThrownBy(() -> driverService.suspend(adminUserId, applicationId, request))
                .isInstanceOf(DriverApplicationNotFoundException.class);

        verifyNoInteractions(driverAuditLogRepository);
    }

    @Test
    @DisplayName("Suspend driver preserves vehicle status (does not mutate vehicle to INACTIVE)")
    void suspend_doesNotModifyVehicleStatus() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.APPROVED);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));
        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(adminUser));

        SuspendDriverRequest request = new SuspendDriverRequest("Safety violation");
        driverService.suspend(adminUserId, applicationId, request);

        assertThat(activeVehicle.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    @DisplayName("Reactivate SUSPENDED driver: successfully transitions to APPROVED and records audit log")
    void reactivate_whenSuspended_updatesStatusToApprovedAndRecordsAuditLog() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.SUSPENDED);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));
        when(userRepository.findById(adminUserId)).thenReturn(Optional.of(adminUser));

        DriverApplicationResponse response = driverService.reactivate(adminUserId, applicationId);

        assertThat(response.status()).isEqualTo(DriverApplicationStatus.APPROVED);
        assertThat(profile.getStatus()).isEqualTo(DriverApplicationStatus.APPROVED);

        ArgumentCaptor<DriverAuditLog> auditCaptor = ArgumentCaptor.forClass(DriverAuditLog.class);
        verify(driverAuditLogRepository, times(1)).save(auditCaptor.capture());

        DriverAuditLog savedLog = auditCaptor.getValue();
        assertThat(savedLog.getAction()).isEqualTo(DriverAuditAction.REACTIVATE);
        assertThat(savedLog.getPreviousStatus()).isEqualTo(DriverApplicationStatus.SUSPENDED);
        assertThat(savedLog.getNewStatus()).isEqualTo(DriverApplicationStatus.APPROVED);
        assertThat(savedLog.getActor()).isEqualTo(adminUser);
    }

    @Test
    @DisplayName("Reactivate APPROVED driver: throws DriverApplicationConflictException")
    void reactivate_whenApproved_throwsConflictException() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.APPROVED);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> driverService.reactivate(adminUserId, applicationId))
                .isInstanceOf(DriverApplicationConflictException.class)
                .hasMessageContaining("Only SUSPENDED driver profiles can be reactivated");

        verifyNoInteractions(driverAuditLogRepository);
    }

    @Test
    @DisplayName("Reactivate PENDING driver: throws DriverApplicationConflictException")
    void reactivate_whenPending_throwsConflictException() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.PENDING);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> driverService.reactivate(adminUserId, applicationId))
                .isInstanceOf(DriverApplicationConflictException.class);

        verifyNoInteractions(driverAuditLogRepository);
    }

    @Test
    @DisplayName("Approve PENDING driver: records APPROVE audit log")
    void approve_recordsApproveAuditLog() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.PENDING);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));
        when(userRepository.findById(reviewerUserId)).thenReturn(Optional.of(reviewerUser));

        Role driverRole = mock(Role.class);
        when(roleRepository.findByCode("DRIVER")).thenReturn(Optional.of(driverRole));
        when(vehicleRepository.findByDriverProfile_Id(applicationId)).thenReturn(List.of(activeVehicle));

        driverService.approve(reviewerUserId, applicationId);

        ArgumentCaptor<DriverAuditLog> auditCaptor = ArgumentCaptor.forClass(DriverAuditLog.class);
        verify(driverAuditLogRepository, times(1)).save(auditCaptor.capture());

        DriverAuditLog savedLog = auditCaptor.getValue();
        assertThat(savedLog.getAction()).isEqualTo(DriverAuditAction.APPROVE);
        assertThat(savedLog.getPreviousStatus()).isEqualTo(DriverApplicationStatus.PENDING);
        assertThat(savedLog.getNewStatus()).isEqualTo(DriverApplicationStatus.APPROVED);
        assertThat(savedLog.getActor()).isEqualTo(reviewerUser);
    }

    @Test
    @DisplayName("Reject PENDING driver: records REJECT audit log with reason")
    void reject_recordsRejectAuditLogWithReason() {
        ReflectionTestUtils.setField(profile, "status", DriverApplicationStatus.PENDING);

        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(profile));
        when(userRepository.findById(reviewerUserId)).thenReturn(Optional.of(reviewerUser));

        RejectDriverRequest request = new RejectDriverRequest("Blurred ID photo");
        driverService.reject(reviewerUserId, applicationId, request);

        ArgumentCaptor<DriverAuditLog> auditCaptor = ArgumentCaptor.forClass(DriverAuditLog.class);
        verify(driverAuditLogRepository, times(1)).save(auditCaptor.capture());

        DriverAuditLog savedLog = auditCaptor.getValue();
        assertThat(savedLog.getAction()).isEqualTo(DriverAuditAction.REJECT);
        assertThat(savedLog.getPreviousStatus()).isEqualTo(DriverApplicationStatus.PENDING);
        assertThat(savedLog.getNewStatus()).isEqualTo(DriverApplicationStatus.REJECTED);
        assertThat(savedLog.getReason()).isEqualTo("Blurred ID photo");
        assertThat(savedLog.getActor()).isEqualTo(reviewerUser);
    }

    @Test
    @DisplayName("Get audit logs for existing profile: returns mapped list in descending order")
    void getAuditLogs_whenApplicationExists_returnsAuditLogsSorted() {
        when(driverProfileRepository.existsById(applicationId)).thenReturn(true);

        DriverAuditLog log1 = new DriverAuditLog(
                profile,
                adminUser,
                DriverAuditAction.SUSPEND,
                DriverApplicationStatus.APPROVED,
                DriverApplicationStatus.SUSPENDED,
                "Policy breach"
        );
        ReflectionTestUtils.setField(log1, "id", UUID.randomUUID());

        DriverAuditLog log2 = new DriverAuditLog(
                profile,
                reviewerUser,
                DriverAuditAction.APPROVE,
                DriverApplicationStatus.PENDING,
                DriverApplicationStatus.APPROVED,
                null
        );
        ReflectionTestUtils.setField(log2, "id", UUID.randomUUID());

        when(driverAuditLogRepository.findByDriverProfile_IdOrderByCreatedAtDesc(applicationId))
                .thenReturn(List.of(log1, log2));

        List<DriverAuditLogResponse> result = driverService.getAuditLogs(applicationId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).action()).isEqualTo(DriverAuditAction.SUSPEND);
        assertThat(result.get(0).newStatus()).isEqualTo(DriverApplicationStatus.SUSPENDED);
        assertThat(result.get(1).action()).isEqualTo(DriverAuditAction.APPROVE);
        assertThat(result.get(1).newStatus()).isEqualTo(DriverApplicationStatus.APPROVED);
    }

    @Test
    @DisplayName("Get audit logs for non-existent profile: throws DriverApplicationNotFoundException")
    void getAuditLogs_whenNotFound_throwsNotFoundException() {
        when(driverProfileRepository.existsById(applicationId)).thenReturn(false);

        assertThatThrownBy(() -> driverService.getAuditLogs(applicationId))
                .isInstanceOf(DriverApplicationNotFoundException.class);

        verify(driverAuditLogRepository, never()).findByDriverProfile_IdOrderByCreatedAtDesc(any());
    }
}
