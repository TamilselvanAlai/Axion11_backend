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

    /** Body: {@code { assetId, idle: boolean, elapsedSeconds: number }} — sibling to
     *  /work-sessions/heartbeat, called on the same ~30s tick while this asset's edit session is
     *  open, so its idle-corrected activeSeconds accumulates in step with the overall work
     *  session. */
    @PostMapping("/tick")
    public ResponseEntity<Void> tick(@RequestBody Map<String, Object> body, @AuthenticationPrincipal User user) {
        Object rawAssetId = body.get("assetId");
        Long assetId = rawAssetId == null ? null : Long.valueOf(String.valueOf(rawAssetId));
        boolean idle = Boolean.TRUE.equals(body.get("idle"));
        long elapsedSeconds = body.get("elapsedSeconds") instanceof Number n ? n.longValue() : 0;
        if (assetId != null) assetEditSessionService.tick(user, assetId, idle, elapsedSeconds);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/today")
    public ResponseEntity<List<AssetEditSessionDto>> today(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(assetEditSessionService.getToday(user));
    }

    /** Self-scoped "my sessions in this date range" — backs the dashboard's This Week/This Month
     *  tabs. Any authenticated user can see their own history here; contrast with
     *  TimeReportController, which spans every user and is restricted to billing roles. */
    @GetMapping("/range")
    public ResponseEntity<List<AssetEditSessionDto>> range(
            @RequestParam("from") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam("to") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(assetEditSessionService.getRange(user, from, to));
    }

    /**
     * GET /api/asset-edit-sessions/asset/{assetId}/total
     * Total logged editing time for this asset across all users — powers the "Production Time"
     * field in the asset info panel, visible to every user regardless of who did the editing.
     */
    @GetMapping("/asset/{assetId}/total")
    public ResponseEntity<Map<String, Long>> assetTotal(@PathVariable("assetId") Long assetId) {
        return ResponseEntity.ok(Map.of("totalSeconds", assetEditSessionService.getTotalProductionSeconds(assetId)));
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
