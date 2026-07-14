package com.axion11.visualops.controller;

import com.axion11.visualops.models.*;
import com.axion11.visualops.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/billing-overview")
@RequiredArgsConstructor
public class BillingOverviewController {

    private final ProjectRepository projectRepository;
    private final BatchRepository batchRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getOverview(
            @RequestParam(value = "projectName", required = false) String projectName) {

        List<Project> projects = projectRepository.findAll();
        List<AuditLog> allLogs = auditLogRepository.findAllByOrderByCreatedAtDesc();
        List<User> allUsers = userRepository.findAll();
        List<Batch> allBatches = batchRepository.findAll();

        // Filter to selected project if specified
        if (projectName != null && !projectName.isEmpty() && !"All Projects".equals(projectName)) {
            projects = projects.stream()
                    .filter(p -> p.getName().equals(projectName))
                    .collect(Collectors.toList());
        }

        Set<Long> projectIds = projects.stream().map(Project::getId).collect(Collectors.toSet());

        // Index audit logs
        Map<Long, List<AuditLog>> auditByAsset = allLogs.stream()
                .filter(a -> a.getAssetId() != null)
                .collect(Collectors.groupingBy(AuditLog::getAssetId));

        Map<Long, Long> assetToBatch = new HashMap<>();
        for (AuditLog a : allLogs) {
            if (a.getAssetId() != null && a.getBatchId() != null)
                assetToBatch.putIfAbsent(a.getAssetId(), a.getBatchId());
        }

        Map<Long, Long> batchToProject = allBatches.stream()
                .collect(Collectors.toMap(Batch::getId, b -> b.getProject().getId(), (a, b) -> a));

        // Batches belonging to selected projects
        Set<Long> relevantBatchIds = allBatches.stream()
                .filter(b -> projectIds.contains(b.getProject().getId()))
                .map(Batch::getId)
                .collect(Collectors.toSet());

        // Assets in those batches
        Set<Long> relevantAssetIds = assetToBatch.entrySet().stream()
                .filter(e -> relevantBatchIds.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // --- KPI: Total Monthly Spend (same as usage ledger cost sum) ---
        double totalSpend = 0;
        for (Project project : projects) {
            if (project.getBillingModel() == null) continue;
            Set<Long> projBatchIds = allBatches.stream()
                    .filter(b -> b.getProject().getId().equals(project.getId()) && b.getTeam() != null)
                    .map(Batch::getId)
                    .collect(Collectors.toSet());
            Set<Long> projAssetIds = assetToBatch.entrySet().stream()
                    .filter(e -> projBatchIds.contains(e.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            totalSpend += computeCost(project, projAssetIds, auditByAsset, allUsers, allBatches);
        }

        // --- KPI: Total Time Logged ---
        long totalTimeSeconds = 0;
        for (Long assetId : relevantAssetIds) {
            totalTimeSeconds += computeAssetTime(assetId, auditByAsset);
        }
        long totalTimeHours = totalTimeSeconds / 3600;

        // --- KPI: Active FTEs ---
        Set<Long> teamIds = allBatches.stream()
                .filter(b -> projectIds.contains(b.getProject().getId()) && b.getTeam() != null)
                .map(b -> b.getTeam().getId())
                .collect(Collectors.toSet());
        long activeFtes = allUsers.stream()
                .filter(u -> u.getTeams().stream().anyMatch(t -> teamIds.contains(t.getId())))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMonthlySpend", Math.round(totalSpend * 100.0) / 100.0);
        result.put("totalTimeLoggedHours", totalTimeHours);
        result.put("activeFtes", activeFtes);

        // --- Pricing Model Breakdown ---
        // Only for a specific selected project
        List<Map<String, Object>> pricingModels = new ArrayList<>();
        if (projects.size() == 1) {
            Project project = projects.get(0);
            if (project.getBillingModel() != null) {
                String currency = project.getCurrency() != null ? project.getCurrency() : "USD";
                String currencySymbol = "USD".equals(currency) ? "$" : currency + " ";

                Set<Long> projBatchIds = allBatches.stream()
                        .filter(b -> b.getProject().getId().equals(project.getId()) && b.getTeam() != null)
                        .map(Batch::getId)
                        .collect(Collectors.toSet());
                Set<Long> projAssetIds = assetToBatch.entrySet().stream()
                        .filter(e -> projBatchIds.contains(e.getValue()))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet());

                switch (project.getBillingModel()) {
                    case "PER_IMAGE": {
                        long imagesProcessed = projAssetIds.stream()
                                .filter(aid -> auditByAsset.getOrDefault(aid, List.of()).stream()
                                        .anyMatch(a -> "READY_FOR_PRODUCTION".equals(a.getEventType())))
                                .count();
                        double rate = 0;
                        if ("FLAT_RATE".equals(project.getImagePricingType()) && project.getPriceFlatRate() != null) {
                            rate = project.getPriceFlatRate().doubleValue();
                        } else if ("CATEGORY".equals(project.getImagePricingType()) && project.getPriceSimple() != null) {
                            rate = project.getPriceSimple().doubleValue();
                        } else if ("PER_SKU".equals(project.getImagePricingType()) && project.getPricePerSku() != null) {
                            rate = project.getPricePerSku().doubleValue();
                        }
                        double subtotal = imagesProcessed * rate;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", "per-image");
                        m.put("name", "Price Per Image");
                        m.put("metric", "Images processed");
                        m.put("quantity", imagesProcessed);
                        m.put("rate", rate);
                        m.put("subtotal", Math.round(subtotal * 100.0) / 100.0);
                        m.put("formula", String.format("%,d images × %s%.2f = %s%,.2f", imagesProcessed, currencySymbol, rate, currencySymbol, subtotal));
                        pricingModels.add(m);
                        break;
                    }
                    case "PER_HOUR": {
                        long projTimeSeconds = 0;
                        for (Long assetId : projAssetIds) {
                            projTimeSeconds += computeAssetTime(assetId, auditByAsset);
                        }
                        double hoursLogged = Math.round(projTimeSeconds / 360.0) / 10.0;
                        double rate = project.getPricePerHour() != null ? project.getPricePerHour().doubleValue() : 0;
                        double subtotal = hoursLogged * rate;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", "time-based");
                        m.put("name", "Time-Based Pricing");
                        m.put("metric", "Hours logged");
                        m.put("quantity", hoursLogged);
                        m.put("rate", rate);
                        m.put("subtotal", Math.round(subtotal * 100.0) / 100.0);
                        m.put("formula", String.format("%.1f hours × %s%.2f/hr = %s%,.2f", hoursLogged, currencySymbol, rate, currencySymbol, subtotal));
                        pricingModels.add(m);
                        break;
                    }
                    case "PER_FTE":
                    case "FTE": {
                        Set<Long> projTeamIds = allBatches.stream()
                                .filter(b -> b.getProject().getId().equals(project.getId()) && b.getTeam() != null)
                                .map(b -> b.getTeam().getId())
                                .collect(Collectors.toSet());
                        long fteCount = allUsers.stream()
                                .filter(u -> u.getTeams().stream().anyMatch(t -> projTeamIds.contains(t.getId())))
                                .count();
                        double rate = project.getPricePerHour() != null ? project.getPricePerHour().doubleValue() * 160 : 0; // monthly
                        double subtotal = fteCount * rate;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", "fte-based");
                        m.put("name", "FTE-Based Pricing");
                        m.put("metric", "Allocated FTEs");
                        m.put("quantity", fteCount);
                        m.put("rate", rate);
                        m.put("subtotal", Math.round(subtotal * 100.0) / 100.0);
                        m.put("formula", String.format("%d FTEs × %s%,.2f/mo = %s%,.2f", fteCount, currencySymbol, rate, currencySymbol, subtotal));
                        pricingModels.add(m);
                        break;
                    }
                }
            }
        } else {
            // All projects: show aggregate per billing type
            double imgTotal = 0, timeTotal = 0, fteTotal = 0;
            long imgQty = 0;
            double timeQty = 0;
            long fteQty = activeFtes;
            for (Project project : projects) {
                if (project.getBillingModel() == null) continue;
                Set<Long> projBatchIds = allBatches.stream()
                        .filter(b -> b.getProject().getId().equals(project.getId()) && b.getTeam() != null)
                        .map(Batch::getId).collect(Collectors.toSet());
                Set<Long> projAssetIds = assetToBatch.entrySet().stream()
                        .filter(e -> projBatchIds.contains(e.getValue()))
                        .map(Map.Entry::getKey).collect(Collectors.toSet());

                switch (project.getBillingModel()) {
                    case "PER_IMAGE": {
                        long count = projAssetIds.stream()
                                .filter(aid -> auditByAsset.getOrDefault(aid, List.of()).stream()
                                        .anyMatch(a -> "READY_FOR_PRODUCTION".equals(a.getEventType())))
                                .count();
                        double rate = 0;
                        if ("FLAT_RATE".equals(project.getImagePricingType()) && project.getPriceFlatRate() != null)
                            rate = project.getPriceFlatRate().doubleValue();
                        imgQty += count;
                        imgTotal += count * rate;
                        break;
                    }
                    case "PER_HOUR": {
                        long s = 0;
                        for (Long aid : projAssetIds) s += computeAssetTime(aid, auditByAsset);
                        double hrs = Math.round(s / 360.0) / 10.0;
                        double rate = project.getPricePerHour() != null ? project.getPricePerHour().doubleValue() : 0;
                        timeQty += hrs;
                        timeTotal += hrs * rate;
                        break;
                    }
                    case "PER_FTE": case "FTE": {
                        double rate = project.getPricePerHour() != null ? project.getPricePerHour().doubleValue() * 160 : 0;
                        Set<Long> tids = allBatches.stream()
                                .filter(b -> b.getProject().getId().equals(project.getId()) && b.getTeam() != null)
                                .map(b -> b.getTeam().getId()).collect(Collectors.toSet());
                        long fc = allUsers.stream().filter(u -> u.getTeams().stream().anyMatch(t -> tids.contains(t.getId()))).count();
                        fteTotal += fc * rate;
                        break;
                    }
                }
            }
            if (imgQty > 0 || imgTotal > 0) {
                double avgRate = imgQty > 0 ? imgTotal / imgQty : 0;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", "per-image"); m.put("name", "Price Per Image"); m.put("metric", "Images processed");
                m.put("quantity", imgQty); m.put("rate", Math.round(avgRate * 100.0) / 100.0);
                m.put("subtotal", Math.round(imgTotal * 100.0) / 100.0);
                m.put("formula", String.format("%,d images × $%.2f = $%,.2f", imgQty, avgRate, imgTotal));
                pricingModels.add(m);
            }
            if (timeQty > 0 || timeTotal > 0) {
                double avgRate = timeQty > 0 ? timeTotal / timeQty : 0;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", "time-based"); m.put("name", "Time-Based Pricing"); m.put("metric", "Hours logged");
                m.put("quantity", timeQty); m.put("rate", Math.round(avgRate * 100.0) / 100.0);
                m.put("subtotal", Math.round(timeTotal * 100.0) / 100.0);
                m.put("formula", String.format("%.1f hours × $%.2f/hr = $%,.2f", timeQty, avgRate, timeTotal));
                pricingModels.add(m);
            }
            if (fteQty > 0 || fteTotal > 0) {
                double avgRate = fteQty > 0 ? fteTotal / fteQty : 0;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", "fte-based"); m.put("name", "FTE-Based Pricing"); m.put("metric", "Allocated FTEs");
                m.put("quantity", fteQty); m.put("rate", Math.round(avgRate * 100.0) / 100.0);
                m.put("subtotal", Math.round(fteTotal * 100.0) / 100.0);
                m.put("formula", String.format("%d FTEs × $%,.2f/mo = $%,.2f", fteQty, avgRate, fteTotal));
                pricingModels.add(m);
            }
        }

        result.put("pricingModels", pricingModels);
        return ResponseEntity.ok(result);
    }

    private double computeCost(Project project, Set<Long> assetIds, Map<Long, List<AuditLog>> auditByAsset,
                               List<User> allUsers, List<Batch> allBatches) {
        switch (project.getBillingModel()) {
            case "PER_IMAGE": {
                long count = assetIds.stream()
                        .filter(aid -> auditByAsset.getOrDefault(aid, List.of()).stream()
                                .anyMatch(a -> "READY_FOR_PRODUCTION".equals(a.getEventType())))
                        .count();
                double rate = 0;
                if ("FLAT_RATE".equals(project.getImagePricingType()) && project.getPriceFlatRate() != null)
                    rate = project.getPriceFlatRate().doubleValue();
                return count * rate;
            }
            case "PER_HOUR": {
                long s = 0;
                for (Long aid : assetIds) s += computeAssetTime(aid, auditByAsset);
                double hrs = Math.round(s / 360.0) / 10.0;
                double rate = project.getPricePerHour() != null ? project.getPricePerHour().doubleValue() : 0;
                return hrs * rate;
            }
            case "PER_FTE": case "FTE": {
                Set<Long> tids = allBatches.stream()
                        .filter(b -> b.getProject().getId().equals(project.getId()) && b.getTeam() != null)
                        .map(b -> b.getTeam().getId()).collect(Collectors.toSet());
                long fc = allUsers.stream().filter(u -> u.getTeams().stream().anyMatch(t -> tids.contains(t.getId()))).count();
                double rate = project.getPricePerHour() != null ? project.getPricePerHour().doubleValue() * 160 : 0;
                return fc * rate;
            }
            default: return 0;
        }
    }

    private long computeAssetTime(Long assetId, Map<Long, List<AuditLog>> auditByAsset) {
        List<AuditLog> logs = auditByAsset.getOrDefault(assetId, List.of());
        OptionalLong startOpt = logs.stream()
                .filter(a -> "ASSIGN_TO_MYSELF".equals(a.getEventType()))
                .mapToLong(a -> a.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC)).min();
        OptionalLong endOpt = logs.stream()
                .filter(a -> "READY_FOR_PRODUCTION".equals(a.getEventType()))
                .mapToLong(a -> a.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC)).max();
        if (startOpt.isPresent() && endOpt.isPresent()) {
            long diff = endOpt.getAsLong() - startOpt.getAsLong();
            return diff > 0 ? diff : 0;
        }
        return 0;
    }
}
