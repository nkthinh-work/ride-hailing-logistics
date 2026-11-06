package com.rhl.trip.dtos;

/**
 * Internal M2M request to mark a trip as NO_DRIVER when candidate matching is exhausted (FR-MAT-015).
 */
public record NoDriverRequest(
        String reason
) {
    public NoDriverRequest() {
        this("Matching limits reached with no driver available");
    }
}
