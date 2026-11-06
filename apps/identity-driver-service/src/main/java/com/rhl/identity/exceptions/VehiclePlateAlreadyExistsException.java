package com.rhl.identity.exceptions;

public class VehiclePlateAlreadyExistsException extends RuntimeException {
    public VehiclePlateAlreadyExistsException(String plate) {
        super("Vehicle plate already registered: " + plate);
    }
}
