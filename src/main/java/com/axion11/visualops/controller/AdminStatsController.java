package com.axion11.visualops.controller;

import com.axion11.visualops.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final ProjectRepository projectRepository;
    private final BatchRepository batchRepository;
    private final ImageUploadRepository imageUploadRepository;
    private final TeamRepository teamRepository;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalProjects", projectRepository.count());
        stats.put("totalBatches", batchRepository.count());
        stats.put("totalAssets", imageUploadRepository.count());
        stats.put("totalTeams", teamRepository.count());
        return ResponseEntity.ok(stats);
    }
}
