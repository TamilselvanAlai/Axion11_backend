package com.axion11.visualops.controller;

import com.axion11.visualops.models.User;
import com.axion11.visualops.models.dto.WorkSessionRangeSummaryDto;
import com.axion11.visualops.models.dto.WorkSessionSummaryDto;
import com.axion11.visualops.service.WorkSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/** Backs the dashboard's "Assets Edited Today" / "Active Editing Time" cards with real
 *  login-to-logout activity instead of static placeholders. The frontend starts a session on
 *  login, sends a heartbeat every ~30s while the app stays open (reporting whether the OS-wide
 *  idle clock crossed the idle threshold since the last tick), records an edit whenever a
 *  locally-opened asset re-syncs after being changed in a 3rd-party app, and ends the session on
 *  logout/close. */
@RestController
@RequestMapping("/api/work-sessions")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class WorkSessionController {

    private final WorkSessionService workSessionService;

    @PostMapping("/start")
    public ResponseEntity<Void> start(@AuthenticationPrincipal User user) {
        workSessionService.startSession(user);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/end")
    public ResponseEntity<Void> end(@AuthenticationPrincipal User user) {
        workSessionService.endSession(user);
        return ResponseEntity.ok().build();
    }

    /** Body: {@code { idle: boolean, idleForApp: boolean, elapsedSeconds: number }} — both flags
     *  come from the same client-side idle streak measured against two bars (10 min for
     *  {@code idle}/activeSeconds, 3 min for {@code idleForApp}/timeInAppSeconds — see
     *  WorkSessionService#heartbeat); {@code elapsedSeconds} is how long since the previous tick
     *  (server clamps this, see WorkSessionService.MAX_TICK_SECONDS, so a delayed/stale tick
     *  can't over-credit time). */
    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody(required = false) Map<String, Object> body,
                                           @AuthenticationPrincipal User user) {
        boolean idle = body != null && Boolean.TRUE.equals(body.get("idle"));
        boolean idleForApp = body != null && Boolean.TRUE.equals(body.get("idleForApp"));
        long elapsedSeconds = body != null && body.get("elapsedSeconds") instanceof Number n ? n.longValue() : 0;
        workSessionService.heartbeat(user, idle, idleForApp, elapsedSeconds);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/record-edit")
    public ResponseEntity<Void> recordEdit(@AuthenticationPrincipal User user) {
        workSessionService.recordAssetEdit(user);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/summary/today")
    public ResponseEntity<WorkSessionSummaryDto> summaryToday(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(workSessionService.getTodaySummary(user));
    }

    /** Self-scoped Active Editing Time / Time In App for an arbitrary date range — backs the
     *  Time Management card's Week/Month tabs. Any authenticated user can see their own history
     *  here (no role restriction), same as /asset-edit-sessions/range. */
    @GetMapping("/summary/range")
    public ResponseEntity<WorkSessionRangeSummaryDto> summaryRange(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(workSessionService.getRangeSummary(user, from, to));
    }
}
