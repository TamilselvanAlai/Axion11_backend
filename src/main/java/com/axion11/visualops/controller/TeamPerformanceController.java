package com.axion11.visualops.controller;

import com.axion11.visualops.models.MemberPerformance;
import com.axion11.visualops.models.TeamPerformance;
import com.axion11.visualops.service.TeamPerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/team-performance")
@RequiredArgsConstructor
public class TeamPerformanceController {

    private final TeamPerformanceService teamPerformanceService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAll() {
        List<TeamPerformance> data = teamPerformanceService.getAll();
        List<Map<String, Object>> result = data.stream().map(tp -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("teamId", tp.getTeamId());
            map.put("teamName", tp.getTeamName());
            map.put("members", tp.getMembers());
            map.put("projects", tp.getProjects());
            map.put("assets", tp.getAssets());
            map.put("avgTimeSeconds", tp.getAvgTimeSeconds());
            map.put("avgTimeFormatted", formatDuration(tp.getAvgTimeSeconds()));
            map.put("totalTimeSeconds", tp.getTotalTimeSeconds());
            map.put("totalTimeFormatted", formatDuration(tp.getTotalTimeSeconds()));
            map.put("approvalPercent", tp.getApprovalPercent());
            map.put("feedback", tp.getFeedback());
            map.put("computedAt", tp.getComputedAt() != null ? tp.getComputedAt().toString() : null);
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/members")
    public ResponseEntity<List<Map<String, Object>>> getAllMembers() {
        List<MemberPerformance> data = teamPerformanceService.getAllMembers();
        List<Map<String, Object>> result = data.stream().map(mp -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", mp.getUserId());
            map.put("userName", mp.getUserName());
            map.put("userRole", mp.getUserRole());
            map.put("teamId", mp.getTeamId());
            map.put("teamName", mp.getTeamName());
            map.put("projects", mp.getProjects());
            map.put("assets", mp.getAssets());
            map.put("avgTimeSeconds", mp.getAvgTimeSeconds());
            map.put("avgTimeFormatted", formatDuration(mp.getAvgTimeSeconds()));
            map.put("totalTimeSeconds", mp.getTotalTimeSeconds());
            map.put("totalTimeFormatted", formatDuration(mp.getTotalTimeSeconds()));
            map.put("approvalPercent", mp.getApprovalPercent());
            map.put("feedback", mp.getFeedback());
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/compute")
    public ResponseEntity<String> compute() {
        teamPerformanceService.computeAll();
        return ResponseEntity.ok("Team performance computed successfully");
    }

    private String formatDuration(Long totalSeconds) {
        if (totalSeconds == null || totalSeconds == 0) return "-";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
