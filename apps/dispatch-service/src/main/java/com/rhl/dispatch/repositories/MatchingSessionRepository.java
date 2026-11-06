package com.rhl.dispatch.repositories;

import com.rhl.dispatch.entities.MatchingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MatchingSessionRepository extends JpaRepository<MatchingSession, UUID> {

    List<MatchingSession> findByTripIdOrderByRoundNumberAsc(UUID tripId);

    java.util.Optional<MatchingSession> findByTripIdAndStatus(UUID tripId, com.rhl.dispatch.models.MatchingSessionStatus status);

    List<MatchingSession> findByStatus(com.rhl.dispatch.models.MatchingSessionStatus status);
}
