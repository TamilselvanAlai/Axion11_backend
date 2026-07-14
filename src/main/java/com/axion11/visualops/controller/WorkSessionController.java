package com.axion11.visualops.controller;

import com.axion11.visualops.models.User;
import com.axion11.visualops.models.dto.WorkSessionSummaryDto;
import com.axion11.visualops.service.WorkSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Backs the dashboard's "Assets Edited Today" / "Active Editing Time" cards with real
 *  login-to-logout activity instead of static placeholders. The frontend starts a session on
 *  login, sends a heartbeat while the app stays open, records an edit whenever a locally-opened
 *  asset re-syncs after being changed in a 3rd-party app, and ends the session on logout/close. */
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

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@AuthenticationPrincipal User user) {
        workSessionService.heartbeat(user);
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
