
package com.rhl.identity.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectDriverRequest(
        @NotBlank
        @Size(max = 500)
        String reason
) {
}
