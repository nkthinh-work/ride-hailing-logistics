package com.rhl.dispatch.models;

/**
 * Status of trip assignment for an accepted driver offer (M06 Group 2.5).
 * Distinguishes an in-flight assignment from historical completed offers.
 */
public enum AssignmentStatus {
    UNASSIGNED,
    PENDING,
    ASSIGNED,
    FAILED
}
