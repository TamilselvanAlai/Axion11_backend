package com.axion11.visualops.service;

import com.axion11.visualops.models.AuditLog;
import com.axion11.visualops.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void log(String eventType, Long projectId, Long batchId, Long assetId, Long userId, String details) {
        AuditLog entry = AuditLog.builder()
                .eventType(eventType)
                .projectId(projectId)
                .batchId(batchId)
                .assetId(assetId)
                .userId(userId)
                .details(details)
                .build();
        auditLogRepository.save(entry);
        log.info("AUDIT [{}] project={} batch={} asset={} user={} — {}", eventType, projectId, batchId, assetId, userId, details);
    }

    public List<AuditLog> getByAsset(Long assetId) {
        return auditLogRepository.findByAssetIdOrderByCreatedAtDesc(assetId);
    }

    public List<AuditLog> getByProject(Long projectId) {
        return auditLogRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public List<AuditLog> getAll() {
        return auditLogRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Most-recent-first, for a single user, restricted to the given event types and time window. */
    public List<AuditLog> getRecentByUserAndTypes(Long userId, List<String> eventTypes, LocalDateTime since) {
        return auditLogRepository.findByUserIdAndEventTypeInAndCreatedAtAfter(userId, eventTypes, since);
    }

    public long getImagesProcessedThisMonth() {
        LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        return auditLogRepository.countDistinctAssetIdSince(startOfMonth);
    }

    public long getImagesProcessedBetween(LocalDateTime from, LocalDateTime to) {
        return auditLogRepository.countDistinctAssetIdBetween(from, to);
    }
}
