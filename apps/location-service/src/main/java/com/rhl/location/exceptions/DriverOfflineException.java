
package com.rhl.location.exceptions;

public class DriverOfflineException extends RuntimeException {

    public DriverOfflineException() {
        super("Driver is offline. Go online before updating GPS");
    }
}
