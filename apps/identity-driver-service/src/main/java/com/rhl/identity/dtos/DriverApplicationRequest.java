
package com.rhl.identity.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DriverApplicationRequest(

        @NotBlank
        @Size(max = 120)
        String fullName,

        @NotBlank
        @Size(max = 20)
        String phone,

        @NotBlank
        @Size(max = 50)
        String driverLicenseNumber,

        @NotBlank
        @Size(max = 20)
        String vehiclePlate

) {
}
