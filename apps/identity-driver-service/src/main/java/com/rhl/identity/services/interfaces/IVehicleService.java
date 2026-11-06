package com.rhl.identity.services.interfaces;

import com.rhl.identity.dtos.VehicleRequest;
import com.rhl.identity.dtos.VehicleResponse;

import java.util.List;
import java.util.UUID;

public interface IVehicleService {

    VehicleResponse registerVehicle(UUID userId, VehicleRequest request);

    List<VehicleResponse> getMyVehicles(UUID userId);

    List<VehicleResponse> getVehiclesByDriverProfileId(UUID driverProfileId);

    VehicleResponse getVehicleById(UUID userId, UUID vehicleId);
}
