package com.rhl.trip.repositories;

import com.rhl.trip.entities.Trip;
import com.rhl.trip.models.TripStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TripRepository extends JpaRepository<Trip, UUID> {

    /**
     * FR-TRIP-002, COM-008: Tìm cuốc xe theo customer và idempotencyKey để đảm bảo idempotency.
     */
    Optional<Trip> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    /**
     * DR-004: Tìm cuốc xe theo quoteId để kiểm tra quote replay.
     */
    Optional<Trip> findByQuoteId(UUID quoteId);

    boolean existsByQuoteId(UUID quoteId);

    /**
     * FR-TRIP-007: Kiểm tra xem khách hàng có cuốc xe nào đang ở trạng thái hoạt động (Active) hay không.
     */
    boolean existsByCustomerIdAndStatusIn(UUID customerId, Collection<TripStatus> statuses);

    /**
     * BR-002: Kiểm tra xem tài xế có cuốc xe nào đang ở trạng thái hoạt động hay không.
     */
    boolean existsByDriverIdAndStatusIn(UUID driverId, Collection<TripStatus> statuses);

    /**
     * M07 Group 2: Lấy cuốc xe đang hoạt động của tài xế để phân phối GPS stream.
     */
    Optional<Trip> findFirstByDriverIdAndStatusIn(UUID driverId, Collection<TripStatus> statuses);

    /**
     * FR-MAT-005, FR-DRV-009: Tìm tập hợp các driverId đang có cuốc xe hoạt động (batch).
     */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT t.driverId FROM Trip t WHERE t.driverId IN :driverIds AND t.status IN :statuses")
    List<UUID> findBusyDriverIds(
            @org.springframework.data.repository.query.Param("driverIds") Collection<UUID> driverIds,
            @org.springframework.data.repository.query.Param("statuses") Collection<TripStatus> statuses
    );

    /**
     * FR-TRIP-015: Lấy danh sách cuốc xe của khách hàng.
     */
    List<Trip> findByCustomerIdOrderByRequestedAtDesc(UUID customerId);

    /**
     * FR-TRIP-015: Lấy danh sách cuốc xe của tài xế.
     */
    List<Trip> findByDriverIdOrderByRequestedAtDesc(UUID driverId);
}
