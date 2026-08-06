package com.campusguard.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
                @Pattern(
                        regexp = "[a-zA-Z0-9_]{3,50}",
                        message = "must be 3-50 characters of letters, digits or underscores")
                String username,

        // Length is the only rule enforced here. Composition rules push people
        // towards short, predictable passwords, and the hash is what actually
        // carries the security.
        @NotBlank @Size(min = 8, max = 128) String password) {
}
