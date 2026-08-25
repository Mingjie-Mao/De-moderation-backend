package com.campusguard.moderation;

import com.campusguard.moderation.engine.EngineRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only capability discovery used by forum clients. */
@RestController
@RequestMapping("/api/moderation/status")
@Tag(name = "Moderation status", description = "Active automated review capability")
public class ModerationStatusController {

    private final EngineRegistry engines;
    private final ModerationProperties properties;

    public ModerationStatusController(EngineRegistry engines, ModerationProperties properties) {
        this.engines = engines;
        this.properties = properties;
    }

    @GetMapping
    @Operation(summary = "Show which moderation engine will judge newly filed reports")
    public ModerationStatusResponse get() {
        String active = engines.primary().name();
        String fallback = engines.fallback().name();
        boolean configuredAvailable = engines.primaryIsAvailable();

        return new ModerationStatusResponse(
                properties.engine(),
                active,
                fallback,
                configuredAvailable,
                configuredAvailable && engines.primary().isLanguageModel());
    }
}
