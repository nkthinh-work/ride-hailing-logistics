package com.rhl.dispatch.models;

/**
 * Diagnostic and audit reasons for driver candidate exclusion during matching (FR-MAT-002, 005, FR-DRV-008..014).
 */
public enum CandidateExclusionReason {
    /** Driver GPS position age exceeds maximum allowable freshness threshold (60s). */
    STALE_GPS,

    /** Driver GPS timestamp is skewed into future beyond allowable tolerance (15s). */
    GPS_IN_FUTURE,

    /** Driver location is missing an authoritative timestamp. */
    MISSING_GPS_TIMESTAMP,

    /** Driver failed eligibility criteria in identity-driver-service. */
    DRIVER_INELIGIBLE,

    /** Driver user account is not in ACTIVE status. */
    ACCOUNT_INACTIVE,

    /** Driver application profile is not in APPROVED status. */
    PROFILE_NOT_APPROVED,

    /** Driver application profile is in SUSPENDED status. */
    DRIVER_SUSPENDED,

    /** Driver does not possess any ACTIVE vehicle. */
    NO_ACTIVE_VEHICLE,

    /** Driver's active vehicle type does not match the vehicle type required by the trip. */
    VEHICLE_TYPE_MISMATCH,

    /** Driver has multiple active vehicles without a single active vehicle session (fail-closed). */
    AMBIGUOUS_ACTIVE_VEHICLES,

    /** Driver currently has a PENDING offer in Dispatch Service (FR-DRV-012). */
    HAS_PENDING_OFFER,

    /** Driver has an ACCEPTED offer where trip assignment is still in progress. */
    ACCEPTED_PENDING_ASSIGNMENT,

    /** Driver is currently assigned to an active trip in Trip Service (FR-MAT-005). */
    DRIVER_BUSY_IN_TRIP,

    /** Driver already rejected an offer for this trip in an earlier matching round (FR-MAT-013). */
    PREVIOUSLY_REJECTED,

    /** Location service failed or timed out during nearby search (fail-closed). */
    LOCATION_SERVICE_UNAVAILABLE,

    /** Identity service failed or timed out during eligibility verification (fail-closed). */
    IDENTITY_SERVICE_UNAVAILABLE,

    /** Trip service failed or timed out during availability check (fail-closed). */
    TRIP_SERVICE_UNAVAILABLE
}
