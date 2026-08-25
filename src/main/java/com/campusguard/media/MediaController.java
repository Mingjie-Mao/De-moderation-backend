package com.campusguard.media;

import com.campusguard.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
public class MediaController {
    private final MediaService service;
    public MediaController(MediaService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Upload and normalize a JPEG or PNG before attaching it to content")
    public MediaResponse upload(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") @NotNull MultipartFile file) {
        return service.upload(AuthenticatedUser.idOf(jwt), file);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read a public media object referenced by forum content")
    public ResponseEntity<byte[]> read(@PathVariable UUID id) {
        MediaService.StoredMedia stored = service.read(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.metadata().getContentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic().immutable())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(stored.bytes());
    }
}
