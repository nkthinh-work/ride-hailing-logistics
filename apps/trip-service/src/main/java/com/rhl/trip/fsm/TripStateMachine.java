package com.rhl.trip.fsm;

import com.rhl.trip.exceptions.InvalidTripStateTransitionException;
import com.rhl.trip.models.TripStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Strict Finite State Machine implementing the Trip Lifecycle specified in SRS-RHL-002 (Section 7.1).
 */
public final class TripStateMachine {

    private static final Map<TripStatus, Set<TripStatus>> VALID_TRANSITIONS = new EnumMap<>(TripStatus.class);

    static {
        // CREATED: Khởi tạo cuốc xe
        VALID_TRANSITIONS.put(TripStatus.CREATED, EnumSet.of(TripStatus.MATCHING, TripStatus.CANCELLED));

        // MATCHING: Đang tìm và gửi offer cho tài xế
        VALID_TRANSITIONS.put(TripStatus.MATCHING, EnumSet.of(TripStatus.ACCEPTED, TripStatus.NO_DRIVER, TripStatus.CANCELLED));

        // ACCEPTED: Tài xế đã chấp nhận offer nguyên tử
        VALID_TRANSITIONS.put(TripStatus.ACCEPTED, EnumSet.of(TripStatus.PICKING_UP, TripStatus.CANCELLED));

        // PICKING_UP: Tài xế đang di chuyển tới điểm đón
        VALID_TRANSITIONS.put(TripStatus.PICKING_UP, EnumSet.of(TripStatus.ARRIVED, TripStatus.CANCELLED));

        // ARRIVED: Tài xế đã tới điểm đón và xác nhận đến
        VALID_TRANSITIONS.put(TripStatus.ARRIVED, EnumSet.of(TripStatus.IN_TRIP, TripStatus.CANCELLED));

        // IN_TRIP: Khách/hàng đã lên xe; chuyến đang diễn ra
        VALID_TRANSITIONS.put(TripStatus.IN_TRIP, EnumSet.of(TripStatus.COMPLETED, TripStatus.CANCELLED));

        // Terminal States: Không có bước chuyển đi
        VALID_TRANSITIONS.put(TripStatus.COMPLETED, Collections.emptySet());
        VALID_TRANSITIONS.put(TripStatus.CANCELLED, Collections.emptySet());
        VALID_TRANSITIONS.put(TripStatus.NO_DRIVER, Collections.emptySet());
    }

    private TripStateMachine() {
        // Utility class
    }

    /**
     * Kiểm tra xem bước chuyển trạng thái có hợp lệ theo ma trận SRS 7.1 hay không.
     */
    public static boolean isValidTransition(TripStatus from, TripStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<TripStatus> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Xác thực bước chuyển trạng thái và quyền của actor.
     * Ném InvalidTripStateTransitionException nếu vi phạm.
     */
    public static void validateTransition(TripStatus from, TripStatus to, String actorRole) {
        if (from == null || to == null) {
            throw new InvalidTripStateTransitionException(from, to, "Source and target trip status must not be null");
        }

        if (from.isTerminal()) {
            throw new InvalidTripStateTransitionException(from, to,
                    "Cannot transition from terminal state " + from + " to " + to);
        }

        if (!isValidTransition(from, to)) {
            throw new InvalidTripStateTransitionException(from, to,
                    "Invalid state transition from " + from + " to " + to + " according to SRS 7.1");
        }

        // Validate actor authorization for specific transitions
        validateActorRole(from, to, actorRole);
    }

    private static void validateActorRole(TripStatus from, TripStatus to, String actorRole) {
        if (actorRole == null || actorRole.isBlank()) {
            throw new InvalidTripStateTransitionException(from, to, "Actor role is required for state transition");
        }

        String role = actorRole.toUpperCase();

        if (to == TripStatus.CANCELLED) {
            validateCancellationActor(from, role);
            return;
        }

        switch (to) {
            case MATCHING -> {
                if (!role.equals("SYSTEM") && !role.equals("ADMINISTRATOR")) {
                    throw new InvalidTripStateTransitionException(from, to,
                            "Only SYSTEM or ADMINISTRATOR can transition trip from CREATED to MATCHING");
                }
            }
            case ACCEPTED -> {
                if (!role.equals("DRIVER")) {
                    throw new InvalidTripStateTransitionException(from, to,
                            "Only DRIVER can accept dispatch offer and transition trip to ACCEPTED");
                }
            }
            case PICKING_UP, ARRIVED, IN_TRIP -> {
                if (!role.equals("DRIVER")) {
                    throw new InvalidTripStateTransitionException(from, to,
                            "Only assigned DRIVER can update trip progress to " + to);
                }
            }
            case COMPLETED -> {
                if (!role.equals("DRIVER")) {
                    throw new InvalidTripStateTransitionException(from, to,
                            "Only assigned DRIVER can complete trip at destination");
                }
            }
            case NO_DRIVER -> {
                if (!role.equals("SYSTEM") && !role.equals("ADMINISTRATOR")) {
                    throw new InvalidTripStateTransitionException(from, to,
                            "Only SYSTEM can mark trip as NO_DRIVER when matching limits are exhausted");
                }
            }
            default -> {
                // Handled above
            }
        }
    }

    private static void validateCancellationActor(TripStatus from, String role) {
        switch (from) {
            case CREATED -> {
                // SRS 7.1: Khách hủy trước matching theo chính sách
                if (!role.equals("CUSTOMER") && !role.equals("SUPPORT_STAFF") && !role.equals("ADMINISTRATOR")) {
                    throw new InvalidTripStateTransitionException(from, TripStatus.CANCELLED,
                            "Only CUSTOMER, SUPPORT_STAFF or ADMINISTRATOR can cancel CREATED trip");
                }
            }
            case MATCHING -> {
                // SRS 7.1: Khách/nhân viên hủy hợp lệ
                if (!role.equals("CUSTOMER") && !role.equals("SUPPORT_STAFF") && !role.equals("ADMINISTRATOR")) {
                    throw new InvalidTripStateTransitionException(from, TripStatus.CANCELLED,
                            "Only CUSTOMER, SUPPORT_STAFF or ADMINISTRATOR can cancel MATCHING trip");
                }
            }
            case ACCEPTED, PICKING_UP, ARRIVED -> {
                // SRS 7.1: Hủy hợp lệ và xử lý phí nếu có (Customer, Driver, SupportStaff, Admin)
                if (!role.equals("CUSTOMER") && !role.equals("DRIVER") && !role.equals("SUPPORT_STAFF") && !role.equals("ADMINISTRATOR")) {
                    throw new InvalidTripStateTransitionException(from, TripStatus.CANCELLED,
                            "Actor " + role + " is not authorized to cancel trip in state " + from);
                }
            }
            case IN_TRIP -> {
                // SRS 7.1: "Chỉ qua quy trình ngoại lệ được phê duyệt"
                if (!role.equals("SUPPORT_STAFF") && !role.equals("ADMINISTRATOR")) {
                    throw new InvalidTripStateTransitionException(from, TripStatus.CANCELLED,
                            "Trip in IN_TRIP status can only be cancelled via approved exception workflow by SUPPORT_STAFF or ADMINISTRATOR");
                }
            }
            default -> throw new InvalidTripStateTransitionException(from, TripStatus.CANCELLED,
                    "Cannot cancel trip from state " + from);
        }
    }

    public static Set<TripStatus> getAllowedNextStates(TripStatus current) {
        if (current == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(VALID_TRANSITIONS.getOrDefault(current, Collections.emptySet()));
    }
}
