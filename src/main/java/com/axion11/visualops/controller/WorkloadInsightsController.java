package com.axion11.visualops.controller;

import com.axion11.visualops.models.*;
import com.axion11.visualops.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/workload-insights")
@RequiredArgsConstructor
public class WorkloadInsightsController {

    private final BatchRepository batchRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ImageUploadRepository imageUploadRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getInsights(
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "batchId", required = false) Long batchId) {

        List<Batch> batches;
        if (batchId != null) {
            batches = batchRepository.findById(batchId).map(List::of).orElse(List.of());
        } else if (projectId != null) {
            batches = batchRepository.findByProjectId(projectId);
        } else {
            batches = batchRepository.findAll();
        }

        Set<Long> batchIds = batches.stream().map(Batch::getId).collect(Collectors.toSet());

        // --- Avg Processing Time ---
        List<AuditLog> allLogs = auditLogRepository.findAllByOrderByCreatedAtDesc();
        // Map assetId -> batchId from audit logs
        Map<Long, Long> assetToBatch = new HashMap<>();
        for (AuditLog a : allLogs) {
            if (a.getAssetId() != null && a.getBatchId() != null) {
                assetToBatch.putIfAbsent(a.getAssetId(), a.getBatchId());
            }
        }
        // Group audit logs by asset
        Map<Long, List<AuditLog>> auditByAsset = allLogs.stream()
                .filter(a -> a.getAssetId() != null)
                .collect(Collectors.groupingBy(AuditLog::getAssetId));

        // Filter to assets belonging to selected batches
        Set<Long> relevantAssets = assetToBatch.entrySet().stream()
                .filter(e -> batchIds.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        long totalDiffSeconds = 0;
        int assetsWithTime = 0;
        for (Long assetId : relevantAssets) {
            List<AuditLog> logs = auditByAsset.getOrDefault(assetId, List.of());
            OptionalLong startOpt = logs.stream()
                    .filter(a -> "ASSIGN_TO_MYSELF".equals(a.getEventType()))
                    .mapToLong(a -> a.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC))
                    .min();
            OptionalLong endOpt = logs.stream()
                    .filter(a -> "READY_FOR_PRODUCTION".equals(a.getEventType()))
                    .mapToLong(a -> a.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC))
                    .max();
            if (startOpt.isPresent() && endOpt.isPresent()) {
                long diff = endOpt.getAsLong() - startOpt.getAsLong();
                if (diff > 0) {
                    totalDiffSeconds += diff;
                    assetsWithTime++;
                }
            }
        }
        long avgSeconds = assetsWithTime > 0 ? totalDiffSeconds / assetsWithTime : 0;
        String avgProcessingTime = formatDuration(avgSeconds);

        // --- Active Team Members ---
        Set<Long> teamIds = batches.stream()
                .filter(b -> b.getTeam() != null)
                .map(b -> b.getTeam().getId())
                .collect(Collectors.toSet());
        long activeMembers = userRepository.findAll().stream()
                .filter(u -> u.getTeams().stream().anyMatch(t -> teamIds.contains(t.getId())))
                .count();

        // --- ETA to Completion (formatted as dd-MMM) ---
        String etaToCompletion = "-";
        Optional<String> earliestEta = batches.stream()
                .map(Batch::getDueDate)
                .filter(Objects::nonNull)
                .filter(d -> !d.isBlank())
                .min(Comparator.naturalOrder());
        if (earliestEta.isPresent()) {
            etaToCompletion = formatAsDdMmm(earliestEta.get());
        }

        // --- Upcoming in Queue: top 4 batches by earliest ETA ---
        List<Map<String, Object>> upcomingQueue = batches.stream()
                .filter(b -> b.getDueDate() != null && !b.getDueDate().isBlank())
                .sorted(Comparator.comparing(Batch::getDueDate))
                .limit(4)
                .map(b -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", b.getName());
                    // Count assets (image_uploads) in this batch
                    long assetCount = imageUploadRepository.findByBatchIdOrderByCreatedAtDesc(b.getId()).size();
                    item.put("assetCount", assetCount);
                    item.put("dueDate", b.getDueDate());
                    // Calculate days/hours pending from now
                    String etaPending = calculateEtaPending(b.getDueDate());
                    item.put("etaPending", etaPending);
                    return item;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("avgProcessingTime", avgProcessingTime);
        result.put("avgProcessingTimeSeconds", avgSeconds);
        result.put("activeTeamMembers", activeMembers);
        result.put("etaToCompletion", etaToCompletion);
        result.put("upcomingQueue", upcomingQueue);

        return ResponseEntity.ok(result);
    }

    private String formatDuration(long totalSeconds) {
        if (totalSeconds == 0) return "-";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + "." + (minutes * 10 / 60) + "h";
        }
        return minutes + "m";
    }

    private static final DateTimeFormatter DD_MMM = DateTimeFormatter.ofPattern("dd-MMM", Locale.ENGLISH);

    private String formatAsDdMmm(String dueDateStr) {
        for (DateTimeFormatter fmt : List.of(
                DateTimeFormatter.ofPattern("MMM d, yyyy"),
                DateTimeFormatter.ofPattern("MMM dd, yyyy"),
                DateTimeFormatter.ISO_LOCAL_DATE)) {
            try {
                LocalDate date = LocalDate.parse(dueDateStr, fmt);
                return date.format(DD_MMM);
            } catch (Exception ignored) {}
        }
        return dueDateStr;
    }

    private String calculateEtaPending(String dueDateStr) {
        try {
            // Try common formats
            LocalDate dueDate = null;
            for (DateTimeFormatter fmt : List.of(
                    DateTimeFormatter.ofPattern("MMM d, yyyy"),
                    DateTimeFormatter.ofPattern("MMM dd, yyyy"),
                    DateTimeFormatter.ISO_LOCAL_DATE)) {
                try {
                    dueDate = LocalDate.parse(dueDateStr, fmt);
                    break;
                } catch (Exception ignored) {}
            }
            if (dueDate == null) return dueDateStr;

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime due = dueDate.atStartOfDay();
            long totalHours = ChronoUnit.HOURS.between(now, due);
            if (totalHours < 0) return "Overdue";
            long days = totalHours / 24;
            long hours = totalHours % 24;
            if (days > 0) return days + "d " + hours + "h";
            return hours + "h";
        } catch (Exception e) {
            return dueDateStr;
        }
    }
}
