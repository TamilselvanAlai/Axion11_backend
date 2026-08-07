package com.axion11.visualops.controller;

import com.axion11.visualops.models.User;
import com.axion11.visualops.models.dto.WorkSessionSummaryDto;
import com.axion11.visualops.service.WorkSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    /** Body: {@code { idle: boolean, elapsedSeconds: number }} — {@code idle} is whether the
     *  client's system-wide idle clock was already past the 10-minute threshold at tick time;
     *  {@code elapsedSeconds} is how long since the previous tick (server clamps this, see
     *  WorkSessionService.MAX_TICK_SECONDS, so a delayed/stale tick can't over-credit time). */
    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody(required = false) Map<String, Object> body,
                                           @AuthenticationPrincipal User user) {
        boolean idle = body != null && Boolean.TRUE.equals(body.get("idle"));
        long elapsedSeconds = body != null && body.get("elapsedSeconds") instanceof Number n ? n.longValue() : 0;
        workSessionService.heartbeat(user, idle, elapsedSeconds);
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
}
