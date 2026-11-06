package com.rhl.trip.repositories;

import com.rhl.trip.entities.TripStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TripStatusHistoryRepository extends JpaRepository<TripStatusHistory, UUID> {

    /**
     * FR-TRIP-010, DR-005: Truy vấn lịch sử chuyển trạng thái của một cuốc xe theo thứ tự thời gian.
     */
    List<TripStatusHistory> findByTripIdOrderByCreatedAtAsc(UUID tripId);
}
