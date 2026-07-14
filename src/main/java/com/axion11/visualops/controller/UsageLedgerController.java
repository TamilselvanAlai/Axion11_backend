package com.axion11.visualops.controller;

import com.axion11.visualops.models.*;
import com.axion11.visualops.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/usage-ledger")
@RequiredArgsConstructor
public class UsageLedgerController {

    private final ProjectRepository projectRepository;
    private final BatchRepository batchRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getLedger() {
        List<Project> projects = projectRepository.findAll();
        List<AuditLog> allLogs = auditLogRepository.findAllByOrderByCreatedAtDesc();
        List<User> allUsers = userRepository.findAll();
        List<Batch> allBatches = batchRepository.findAll();

        // Index audit logs by assetId
        Map<Long, List<AuditLog>> auditByAsset = allLogs.stream()
                .filter(a -> a.getAssetId() != null)
                .collect(Collectors.groupingBy(AuditLog::getAssetId));

        // Map assetId -> batchId
        Map<Long, Long> assetToBatch = new HashMap<>();
        for (AuditLog a : allLogs) {
            if (a.getAssetId() != null && a.getBatchId() != null) {
                assetToBatch.putIfAbsent(a.getAssetId(), a.getBatchId());
            }
        }

        // Map batchId -> teamId
        Map<Long, Long> batchToTeam = allBatches.stream()
                .filter(b -> b.getTeam() != null)
                .collect(Collectors.toMap(Batch::getId, b -> b.getTeam().getId(), (a, b) -> a));

        // Map teamId -> teamName
        Map<Long, String> teamNames = allBatches.stream()
                .filter(b -> b.getTeam() != null)
                .collect(Collectors.toMap(b -> b.getTeam().getId(), b -> b.getTeam().getTeamName(), (a, b) -> a));

        // Map batchId -> projectId
        Map<Long, Long> batchToProject = allBatches.stream()
                .collect(Collectors.toMap(Batch::getId, b -> b.getProject().getId(), (a, b) -> a));

        // Group users by teamId (a user in multiple teams appears under each team)
        Map<Long, List<User>> usersByTeam = new java.util.HashMap<>();
        for (User u : allUsers) {
            for (com.axion11.visualops.models.Team t : u.getTeams()) {
                usersByTeam.computeIfAbsent(t.getId(), k -> new ArrayList<>()).add(u);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        int idCounter = 1;

        for (Project project : projects) {
            if (project.getBillingModel() == null) continue;

            Long projectId = project.getId();
            String billingModel = project.getBillingModel();
            String currency = project.getCurrency() != null ? project.getCurrency() : "USD";

            // Find teams assigned to this project via batches
            Set<Long> projectTeamIds = allBatches.stream()
                    .filter(b -> b.getProject().getId().equals(projectId) && b.getTeam() != null)
                    .map(b -> b.getTeam().getId())
                    .collect(Collectors.toSet());

            if (projectTeamIds.isEmpty()) continue;

            for (Long teamId : projectTeamIds) {
                String teamName = teamNames.getOrDefault(teamId, "Unknown");

                // Batches for this team+project
                Set<Long> teamBatchIds = allBatches.stream()
                        .filter(b -> b.getProject().getId().equals(projectId)
                                && b.getTeam() != null && b.getTeam().getId().equals(teamId))
                        .map(Batch::getId)
                        .collect(Collectors.toSet());

                // Assets in those batches
                Set<Long> teamAssetIds = assetToBatch.entrySet().stream()
                        .filter(e -> teamBatchIds.contains(e.getValue()))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet());

                String billingTypeDisplay;
                double quantity = 0;
                double rate = 0;

                switch (billingModel) {
                    case "PER_IMAGE":
                        billingTypeDisplay = "Image";
                        // Count distinct assets in READY_FOR_PRODUCTION
                        quantity = teamAssetIds.stream()
                                .filter(assetId -> auditByAsset.getOrDefault(assetId, List.of()).stream()
                                        .anyMatch(a -> "READY_FOR_PRODUCTION".equals(a.getEventType())))
                                .count();
                        // Rate based on image_pricing_type
                        if ("FLAT_RATE".equals(project.getImagePricingType()) && project.getPriceFlatRate() != null) {
                            rate = project.getPriceFlatRate().doubleValue();
                        } else if ("CATEGORY".equals(project.getImagePricingType())) {
                            // Use simple price as default for category
                            rate = project.getPriceSimple() != null ? project.getPriceSimple().doubleValue() : 0;
                        } else if ("PER_SKU".equals(project.getImagePricingType()) && project.getPricePerSku() != null) {
                            rate = project.getPricePerSku().doubleValue();
                        }
                        break;

                    case "PER_HOUR":
                        billingTypeDisplay = "Time";
                        // Calculate total hours
                        long totalSeconds = 0;
                        for (Long assetId : teamAssetIds) {
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
                                if (diff > 0) totalSeconds += diff;
                            }
                        }
                        // Convert to hours with 1 decimal
                        quantity = Math.round(totalSeconds / 360.0) / 10.0;
                        rate = project.getPricePerHour() != null ? project.getPricePerHour().doubleValue() : 0;
                        break;

                    case "PER_FTE":
                    case "FTE":
                        billingTypeDisplay = "FTE";
                        // Count team members with Editor/QC roles
                        List<User> teamUsers = usersByTeam.getOrDefault(teamId, List.of());
                        long editorQcCount = teamUsers.stream()
                                .filter(u -> u.getRole() != null)
                                .filter(u -> u.getRole() == Role.DESIGNER || u.getRole() == Role.REVIEWER)
                                .count();
                        // Find distinct working days from audit logs for this team's assets
                        Set<LocalDate> workingDays = new HashSet<>();
                        for (Long assetId : teamAssetIds) {
                            auditByAsset.getOrDefault(assetId, List.of()).stream()
                                    .filter(a -> a.getCreatedAt() != null)
                                    .forEach(a -> workingDays.add(a.getCreatedAt().toLocalDate()));
                        }
                        int daysWorked = workingDays.isEmpty() ? 1 : workingDays.size();
                        quantity = editorQcCount * daysWorked;
                        rate = project.getPricePerHour() != null ? project.getPricePerHour().doubleValue() * 8 : 0;
                        break;

                    default:
                        continue;
                }

                double cost = BigDecimal.valueOf(quantity)
                        .multiply(BigDecimal.valueOf(rate))
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", String.valueOf(idCounter++));
                entry.put("date", "2026-03-22");
                entry.put("team", teamName);
                entry.put("project", project.getName());
                entry.put("billingType", billingTypeDisplay);
                entry.put("quantity", quantity);
                entry.put("rate", rate);
                entry.put("cost", cost);
                entry.put("currency", currency);
                result.add(entry);
            }
        }

        return ResponseEntity.ok(result);
    }
}
