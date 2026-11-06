package com.rhl.identity.services;

import com.rhl.identity.dtos.RejectDriverRequest;
import com.rhl.identity.dtos.SuspendDriverRequest;
import com.rhl.identity.entities.enums.DriverApplicationStatus;
import com.rhl.identity.repositories.DriverAuditLogRepository;
import com.rhl.identity.repositories.DriverProfileRepository;
import com.rhl.identity.repositories.RoleRepository;
import com.rhl.identity.repositories.UserRepository;
import com.rhl.identity.services.interfaces.IDriverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.rhl.identity.repositories.VehicleRepository;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DriverServiceSecurityTest.TestConfig.class)
class DriverServiceSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        public IDriverService driverService(
                DriverProfileRepository driverProfileRepository,
                UserRepository userRepository,
                RoleRepository roleRepository,
                VehicleRepository vehicleRepository,
                DriverAuditLogRepository driverAuditLogRepository) {
            return new DriverService(driverProfileRepository, userRepository, roleRepository, vehicleRepository, driverAuditLogRepository);
        }
    }

    @MockitoBean
    private DriverProfileRepository driverProfileRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private VehicleRepository vehicleRepository;

    @MockitoBean
    private DriverAuditLogRepository driverAuditLogRepository;

    @Autowired
    private IDriverService driverService;

    private final UUID reviewerId = UUID.randomUUID();
    private final UUID administratorId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();

    @Test
    @WithMockUser(authorities = "ROLE_CUSTOMER")
    @DisplayName("DriverService.listApplications with ROLE_CUSTOMER throws AccessDeniedException")
    void listApplications_customerAuthority_throwsAccessDenied() {
        assertThatThrownBy(() -> driverService.listApplications(DriverApplicationStatus.PENDING))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(driverProfileRepository);
    }

    @Test
    @WithMockUser(authorities = "ROLE_CUSTOMER")
    @DisplayName("DriverService.approve with ROLE_CUSTOMER throws AccessDeniedException")
    void approve_customerAuthority_throwsAccessDenied() {
        assertThatThrownBy(() -> driverService.approve(reviewerId, applicationId))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(driverProfileRepository);
    }

    @Test
    @WithMockUser(authorities = "ROLE_CUSTOMER")
    @DisplayName("DriverService.reject with ROLE_CUSTOMER throws AccessDeniedException")
    void reject_customerAuthority_throwsAccessDenied() {
        RejectDriverRequest request = new RejectDriverRequest("Invalid docs");
        assertThatThrownBy(() -> driverService.reject(reviewerId, applicationId, request))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(driverProfileRepository);
    }

    @Test
    @WithMockUser(authorities = "DRIVER_REVIEW")
    @DisplayName("DriverService.listApplications with DRIVER_REVIEW authority passes method security")
    void listApplications_driverReviewAuthority_allowed() {
        when(driverProfileRepository.findByStatus(DriverApplicationStatus.PENDING))
                .thenReturn(Collections.emptyList());

        var result = driverService.listApplications(DriverApplicationStatus.PENDING);
        assertThat(result).isEmpty();

        verify(driverProfileRepository, times(1)).findByStatus(DriverApplicationStatus.PENDING);
    }

    @Test
    @WithMockUser(authorities = "DRIVER_REVIEW")
    @DisplayName("DriverService.suspend with DRIVER_REVIEW authority (Reviewer) throws AccessDeniedException")
    void suspend_reviewerAuthority_throwsAccessDenied() {
        SuspendDriverRequest request = new SuspendDriverRequest("Policy violation");
        assertThatThrownBy(() -> driverService.suspend(reviewerId, applicationId, request))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(driverProfileRepository);
    }

    @Test
    @WithMockUser(authorities = "DRIVER_REVIEW")
    @DisplayName("DriverService.reactivate with DRIVER_REVIEW authority (Reviewer) throws AccessDeniedException")
    void reactivate_reviewerAuthority_throwsAccessDenied() {
        assertThatThrownBy(() -> driverService.reactivate(reviewerId, applicationId))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(driverProfileRepository);
    }

    @Test
    @WithMockUser(authorities = "ROLE_CUSTOMER")
    @DisplayName("DriverService.getAuditLogs with ROLE_CUSTOMER throws AccessDeniedException")
    void getAuditLogs_customerAuthority_throwsAccessDenied() {
        assertThatThrownBy(() -> driverService.getAuditLogs(applicationId))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(driverAuditLogRepository);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMINISTRATOR")
    @DisplayName("DriverService.suspend with ROLE_ADMINISTRATOR passes method security")
    void suspend_administratorAuthority_allowed() {
        com.rhl.identity.entities.DriverProfile profile = mock(com.rhl.identity.entities.DriverProfile.class);
        when(profile.getStatus()).thenReturn(DriverApplicationStatus.APPROVED);
        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(java.util.Optional.of(profile));
        com.rhl.identity.entities.User admin = mock(com.rhl.identity.entities.User.class);
        when(userRepository.findById(administratorId)).thenReturn(java.util.Optional.of(admin));
        when(profile.getUser()).thenReturn(admin);

        SuspendDriverRequest request = new SuspendDriverRequest("Policy violation");
        var result = driverService.suspend(administratorId, applicationId, request);

        assertThat(result).isNotNull();
        verify(profile, times(1)).suspend();
        verify(driverAuditLogRepository, times(1)).save(any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMINISTRATOR")
    @DisplayName("DriverService.reactivate with ROLE_ADMINISTRATOR passes method security")
    void reactivate_administratorAuthority_allowed() {
        com.rhl.identity.entities.DriverProfile profile = mock(com.rhl.identity.entities.DriverProfile.class);
        when(profile.getStatus()).thenReturn(DriverApplicationStatus.SUSPENDED);
        when(driverProfileRepository.findByIdForUpdate(applicationId)).thenReturn(java.util.Optional.of(profile));
        com.rhl.identity.entities.User admin = mock(com.rhl.identity.entities.User.class);
        when(userRepository.findById(administratorId)).thenReturn(java.util.Optional.of(admin));
        when(profile.getUser()).thenReturn(admin);

        var result = driverService.reactivate(administratorId, applicationId);

        assertThat(result).isNotNull();
        verify(profile, times(1)).reactivate();
        verify(driverAuditLogRepository, times(1)).save(any());
    }

    @Test
    @WithMockUser(authorities = "DRIVER_REVIEW")
    @DisplayName("DriverService.getAuditLogs with DRIVER_REVIEW authority passes method security")
    void getAuditLogs_reviewerAuthority_allowed() {
        when(driverProfileRepository.existsById(applicationId)).thenReturn(true);
        when(driverAuditLogRepository.findByDriverProfile_IdOrderByCreatedAtDesc(applicationId))
                .thenReturn(Collections.emptyList());

        var result = driverService.getAuditLogs(applicationId);
        assertThat(result).isEmpty();

        verify(driverAuditLogRepository, times(1))
                .findByDriverProfile_IdOrderByCreatedAtDesc(applicationId);
    }
}
