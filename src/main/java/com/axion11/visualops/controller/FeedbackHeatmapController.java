package com.axion11.visualops.controller;

import com.axion11.visualops.controller.dto.FeedbackHeatmapDto;
import com.axion11.visualops.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/feedback-heatmap")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class FeedbackHeatmapController {

    private final CommentRepository commentRepository;

    // Display names for category keys
    private static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>();
    static {
        CATEGORY_LABELS.put("PHOTOGRAPHY_SOURCE", "Photography Source");
        CATEGORY_LABELS.put("POST_PRODUCTION_PROCESS", "Post Production - Process Error");
        CATEGORY_LABELS.put("POST_PRODUCTION_RETOUCHING", "Post Production - Retouching Error");
        CATEGORY_LABELS.put("PRODUCTION_MANAGEMENT", "Production Management");
    }

    // All expected subcategories per category (preserves order even when count is 0)
    private static final Map<String, List<String>> SUBCATEGORIES = new LinkedHashMap<>();
    static {
        SUBCATEGORIES.put("PHOTOGRAPHY_SOURCE",
                List.of("Dimension", "Resolution", "Sharpness", "Exposure", "Focus"));
        SUBCATEGORIES.put("POST_PRODUCTION_PROCESS",
                List.of("Framing", "Alignment", "Dimension", "PPI/DPI", "Instructions not followed"));
        SUBCATEGORIES.put("POST_PRODUCTION_RETOUCHING",
                List.of("Skin", "Garment", "Product", "Clutter", "Background", "Color Correction"));
        SUBCATEGORIES.put("PRODUCTION_MANAGEMENT",
                List.of("Delay - Assignment", "Delay - Postproduction", "Delay - Description", "Misinformation", "Sample/Info Delay"));
    }

    @GetMapping
    public ResponseEntity<List<FeedbackHeatmapDto>> getHeatmap(
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "batchId", required = false) Long batchId) {

        List<Object[]> rows;
        if (batchId != null) {
            rows = commentRepository.countByFeedbackCategoryForBatch(batchId);
        } else if (projectId != null) {
            rows = commentRepository.countByFeedbackCategoryForProject(projectId);
        } else {
            rows = commentRepository.countByFeedbackCategoryAll();
        }

        // Build a lookup: category -> subcategory -> {count, severity}
        Map<String, Map<String, long[]>> lookup = new HashMap<>();
        Map<String, Map<String, String>> severityLookup = new HashMap<>();
        for (Object[] row : rows) {
            String cat = (String) row[0];
            String sub = (String) row[1];
            String sev = (String) row[2];
            long count = (Long) row[3];
            lookup.computeIfAbsent(cat, k -> new HashMap<>())
                   .merge(sub, new long[]{count}, (a, b) -> { a[0] += b[0]; return a; });
            // Keep highest severity seen
            severityLookup.computeIfAbsent(cat, k -> new HashMap<>())
                          .merge(sub, sev != null ? sev : "low", (a, b) -> maxSeverity(a, b));
        }

        // Build response preserving expected order, including zero-count items
        List<FeedbackHeatmapDto> result = new ArrayList<>();
        for (var catEntry : SUBCATEGORIES.entrySet()) {
            String catKey = catEntry.getKey();
            String catLabel = CATEGORY_LABELS.getOrDefault(catKey, catKey);
            Map<String, long[]> subCounts = lookup.getOrDefault(catKey, Map.of());
            Map<String, String> subSeverities = severityLookup.getOrDefault(catKey, Map.of());

            List<FeedbackHeatmapDto.FeedbackItem> items = catEntry.getValue().stream()
                    .map(sub -> FeedbackHeatmapDto.FeedbackItem.builder()
                            .label(sub)
                            .count(subCounts.containsKey(sub) ? subCounts.get(sub)[0] : 0)
                            .severity(subSeverities.getOrDefault(sub, "low"))
                            .build())
                    .collect(Collectors.toList());

            result.add(FeedbackHeatmapDto.builder()
                    .category(catLabel)
                    .items(items)
                    .build());
        }

        return ResponseEntity.ok(result);
    }

    private static String maxSeverity(String a, String b) {
        Map<String, Integer> rank = Map.of("low", 0, "medium", 1, "high", 2);
        return rank.getOrDefault(a, 0) >= rank.getOrDefault(b, 0) ? a : b;
    }
}
