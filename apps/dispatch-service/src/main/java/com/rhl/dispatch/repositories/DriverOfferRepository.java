package com.rhl.dispatch.repositories;

import com.rhl.dispatch.entities.DriverOffer;
import com.rhl.dispatch.models.AssignmentStatus;
import com.rhl.dispatch.models.OfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DriverOfferRepository extends JpaRepository<DriverOffer, UUID> {

    Optional<DriverOffer> findByTripIdAndStatus(UUID tripId, OfferStatus status);

    Optional<DriverOffer> findByDriverIdAndStatus(UUID driverId, OfferStatus status);

    List<DriverOffer> findByTripId(UUID tripId);

    List<DriverOffer> findByDriverId(UUID driverId);

    boolean existsByTripIdAndStatus(UUID tripId, OfferStatus status);

    boolean existsByTripIdAndStatusAndAssignmentStatusIn(UUID tripId, OfferStatus status, java.util.Collection<AssignmentStatus> assignmentStatuses);

    Optional<DriverOffer> findByTripIdAndStatusAndAssignmentStatusIn(UUID tripId, OfferStatus status, java.util.Collection<AssignmentStatus> assignmentStatuses);

    boolean existsByDriverIdAndStatus(UUID driverId, OfferStatus status);

    boolean existsByDriverIdAndStatusAndAssignmentStatus(UUID driverId, OfferStatus status, AssignmentStatus assignmentStatus);

    boolean existsByDriverIdAndStatusAndAssignmentStatusIn(UUID driverId, OfferStatus status, java.util.Collection<AssignmentStatus> assignmentStatuses);

    List<DriverOffer> findByStatusAndAssignmentStatus(OfferStatus status, AssignmentStatus assignmentStatus);

    @Query("SELECT o FROM DriverOffer o WHERE o.status = :status AND o.expiresAt <= :now")
    List<DriverOffer> findExpiredOffers(@Param("status") OfferStatus status, @Param("now") Instant now);

    @Query("SELECT o FROM DriverOffer o WHERE o.status = 'ACCEPTED' AND o.assignmentStatus = 'PENDING' AND o.updatedAt <= :staleThreshold")
    List<DriverOffer> findPendingAssignmentsForRecovery(@Param("staleThreshold") Instant staleThreshold);
}
