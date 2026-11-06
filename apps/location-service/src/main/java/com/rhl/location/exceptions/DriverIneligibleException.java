package com.rhl.location.exceptions;

import java.util.List;

public class DriverIneligibleException extends RuntimeException {

    private final List<String> reasons;

    public DriverIneligibleException(String message) {
        super(message);
        this.reasons = List.of();
    }

    public DriverIneligibleException(String message, List<String> reasons) {
        super(message);
        this.reasons = reasons != null ? reasons : List.of();
    }

    public List<String> getReasons() {
        return reasons;
    }
}
