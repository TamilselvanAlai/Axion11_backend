package com.axion11.visualops.controller;

import com.axion11.visualops.models.User;
import com.axion11.visualops.models.dto.AssetEditSessionDto;
import com.axion11.visualops.service.AssetEditSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Backs the dashboard's per-asset "time spent editing" breakdown. The frontend starts a
 *  session when Open File/Retouch is clicked and ends it when that asset's edited version
 *  finishes re-syncing — see useWorkSessionTracking on the client side. */
@RestController
@RequestMapping("/api/asset-edit-sessions")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AssetEditSessionController {

    private final AssetEditSessionService assetEditSessionService;

    @PostMapping("/start")
    public ResponseEntity<Void> start(@RequestBody Map<String, String> body, @AuthenticationPrincipal User user) {
        Long assetId = parseAssetId(body);
        if (assetId != null) assetEditSessionService.startSession(user, assetId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/end")
    public ResponseEntity<Void> end(@RequestBody Map<String, String> body, @AuthenticationPrincipal User user) {
        Long assetId = parseAssetId(body);
        if (assetId != null) assetEditSessionService.endSession(user, assetId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/today")
    public ResponseEntity<List<AssetEditSessionDto>> today(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(assetEditSessionService.getToday(user));
    }

    private Long parseAssetId(Map<String, String> body) {
        String raw = body.get("assetId");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
