
package com.rhl.identity.exceptions;

public class DriverApplicationNotFoundException
        extends RuntimeException {

    public DriverApplicationNotFoundException() {
        super("Driver application not found");
    }
}
