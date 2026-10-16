package com.converter.controller;

import com.converter.service.FeatureFlagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class FeatureFlagController {
    private final FeatureFlagService featureFlagService;

    public record FlagUpdate(@NotNull Boolean enabled) {}

    @GetMapping("/api/features")
    public ResponseEntity<Map<String, Boolean>> features() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(featureFlagService.flags());
    }

    @GetMapping("/api/admin/features")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<FeatureFlagService.Administration> administration() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(featureFlagService.administration());
    }

    @PatchMapping("/api/admin/features/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<FeatureFlagService.Administration> update(
            @PathVariable String key,
            @Valid @RequestBody FlagUpdate update,
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        featureFlagService.update(
                                key, update.enabled(), (Long) authentication.getPrincipal()));
    }

    @DeleteMapping("/api/admin/features/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<FeatureFlagService.Administration> reset(
            @PathVariable String key, Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(featureFlagService.reset(key, (Long) authentication.getPrincipal()));
    }
}
