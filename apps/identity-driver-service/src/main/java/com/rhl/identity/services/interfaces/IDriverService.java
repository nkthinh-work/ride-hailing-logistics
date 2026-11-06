package com.rhl.identity.services.interfaces;

import com.rhl.identity.dtos.*;
import com.rhl.identity.entities.enums.DriverApplicationStatus;

import java.util.List;
import java.util.UUID;

public interface IDriverService {

    DriverApplicationResponse apply(
            UUID userId,
            DriverApplicationRequest request
    );

    DriverApplicationResponse getMyApplication(UUID userId);

    List<DriverApplicationResponse> listApplications(
            DriverApplicationStatus status
    );

    DriverApplicationResponse approve(
            UUID reviewerId,
            UUID applicationId
    );

    DriverApplicationResponse reject(
            UUID reviewerId,
            UUID applicationId,
            RejectDriverRequest request
    );

    DriverEligibilityResponse getEligibilityByUserId(UUID userId);

    DriverEligibilityResponse getEligibilityByApplicationId(UUID applicationId);

    DriverApplicationResponse suspend(
            UUID actorId,
            UUID applicationId,
            SuspendDriverRequest request
    );

    DriverApplicationResponse reactivate(
            UUID actorId,
            UUID applicationId
    );

    List<DriverAuditLogResponse> getAuditLogs(
            UUID applicationId
    );

    List<DriverEligibilityResponse> getBatchEligibility(
            List<UUID> userIds
    );
}