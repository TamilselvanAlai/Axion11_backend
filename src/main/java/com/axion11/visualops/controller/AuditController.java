package com.axion11.visualops.controller;

import com.axion11.visualops.controller.dto.AuditLogDto;
import com.axion11.visualops.controller.dto.ImageUploadDto;
import com.axion11.visualops.models.AuditLog;
import com.axion11.visualops.models.ImageUpload;
import com.axion11.visualops.models.User;
import com.axion11.visualops.repository.ImageUploadRepository;
import com.axion11.visualops.repository.UserRepository;
import com.axion11.visualops.service.AuditService;
import com.axion11.visualops.service.ImageUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;
    private final ImageUploadService imageUploadService;
    private final UserRepository userRepository;
    private final ImageUploadRepository imageUploadRepository;

    @GetMapping
    public ResponseEntity<List<AuditLogDto>> getAll() {
        return ResponseEntity.ok(withActorAndVersion(auditService.getAll()));
    }

    @GetMapping("/asset/{assetId}")
    public ResponseEntity<List<AuditLogDto>> getByAsset(@PathVariable("assetId") Long assetId) {
        return ResponseEntity.ok(withActorAndVersion(auditService.getByAsset(assetId)));
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<AuditLogDto>> getByProject(@PathVariable("projectId") Long projectId) {
        return ResponseEntity.ok(withActorAndVersion(auditService.getByProject(projectId)));
    }

    /** Batch-resolves each log's userId → display name and assetId → current version number. */
    private List<AuditLogDto> withActorAndVersion(List<AuditLog> logs) {
        Set<Long> userIds = logs.stream().map(AuditLog::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> assetIds = logs.stream().map(AuditLog::getAssetId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, String> namesByUserId = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        Map<Long, Integer> versionsByAssetId = imageUploadRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(ImageUpload::getId, u -> u.getVersionNumber() != null ? u.getVersionNumber() : 1));

        return logs.stream().map(log -> new AuditLogDto(
                log.getId(),
                log.getEventType(),
                log.getProjectId(),
                log.getBatchId(),
                log.getAssetId(),
                log.getUserId(),
                log.getUserId() != null ? namesByUserId.get(log.getUserId()) : null,
                log.getDetails(),
                log.getCreatedAt(),
                log.getAssetId() != null ? versionsByAssetId.get(log.getAssetId()) : null
        )).collect(Collectors.toList());
    }

    /**
     * GET /api/audit/recent-assets?days=7
     * Assets the current user has actually viewed (ASSET_VIEW), most-recently-viewed first,
     * within the given window — powers the "Recent" page.
     */
    @GetMapping("/recent-assets")
    public ResponseEntity<List<ImageUploadDto>> getRecentAssets(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(recentByEventTypes(days, userDetails, List.of("ASSET_VIEW")));
    }

    /**
     * GET /api/audit/transfers?days=7
     * Assets the current user has uploaded or downloaded, most-recent first, within the given
     * window — powers the "Transfers" page.
     */
    @GetMapping("/transfers")
    public ResponseEntity<List<ImageUploadDto>> getTransfers(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(recentByEventTypes(days, userDetails, List.of("IMAGE_UPLOAD", "ASSET_DOWNLOAD")));
    }

    private List<ImageUploadDto> recentByEventTypes(int days, UserDetails userDetails, List<String> eventTypes) {
        if (userDetails == null) return List.of();
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new NoSuchElementException("User not found"));
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<AuditLog> logs = auditService.getRecentByUserAndTypes(user.getId(), eventTypes, since);

        // Dedupe by assetId, keeping only the most recent entry (logs are already newest-first).
        Set<Long> orderedAssetIds = new LinkedHashSet<>();
        for (AuditLog entry : logs) {
            if (entry.getAssetId() != null) orderedAssetIds.add(entry.getAssetId());
        }
        return imageUploadService.getUploadsByIdsInOrder(List.copyOf(orderedAssetIds));
    }

    @GetMapping("/stats/images-processed")
    public ResponseEntity<Long> getImagesProcessed(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        if (from != null && to != null && !from.isEmpty() && !to.isEmpty()) {
            LocalDate fromDate = LocalDate.parse(from);
            LocalDate toDate = LocalDate.parse(to);
            return ResponseEntity.ok(auditService.getImagesProcessedBetween(
                    fromDate.atStartOfDay(), toDate.plusDays(1).atStartOfDay()));
        }
        return ResponseEntity.ok(auditService.getImagesProcessedThisMonth());
    }
}
