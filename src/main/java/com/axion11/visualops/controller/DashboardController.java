package com.axion11.visualops.controller;

import com.axion11.visualops.models.dto.DashboardBatch;
import com.axion11.visualops.models.dto.DashboardStats;
import com.axion11.visualops.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("isAuthenticated()")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public ResponseEntity<DashboardStats> getSummary() {
        return ResponseEntity.ok(dashboardService.getSummaryStats());
    }

    @GetMapping("/batches")
    public ResponseEntity<List<DashboardBatch>> getBatches() {
        return ResponseEntity.ok(dashboardService.getBatches());
    }
}
