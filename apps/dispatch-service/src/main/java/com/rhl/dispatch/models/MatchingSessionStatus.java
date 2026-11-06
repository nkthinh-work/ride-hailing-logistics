package com.rhl.dispatch.models;

/**
 * Status of a matching session tracking the driver candidate search rounds.
 */
public enum MatchingSessionStatus {
    SEARCHING,
    MATCHED,
    EXPIRED,
    CANCELLED
}
