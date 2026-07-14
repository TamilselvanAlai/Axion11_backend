package com.axion11.visualops.controller;

import com.axion11.visualops.service.ErrorNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/errors")
@RequiredArgsConstructor
public class ErrorNotificationController {

    private final ErrorNotificationService notificationService;

    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> notifyError(@RequestBody Map<String, String> body) {
        String severity = body.getOrDefault("severity", "error");
        String message = body.getOrDefault("message", "Unknown error");
        String rootCause = body.getOrDefault("rootCause", "");
        String suggestedFix = body.getOrDefault("suggestedFix", "");
        String type = body.getOrDefault("type", "unknown");
        String url = body.getOrDefault("url", "");
        String stack = body.getOrDefault("stack", "");

        notificationService.sendErrorEmail(severity, type, message, rootCause, suggestedFix, url, stack);

        return ResponseEntity.ok(Map.of("status", "sent"));
    }
}
