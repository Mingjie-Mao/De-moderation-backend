package com.campusguard.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record UpdatePostRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20_000) String body,
        UUID mediaId) {}
