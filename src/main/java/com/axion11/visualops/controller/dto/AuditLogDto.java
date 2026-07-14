package com.axion11.visualops.controller.dto;

import java.time.LocalDateTime;

public record AuditLogDto(
        Long id,
        String eventType,
        Long projectId,
        Long batchId,
        Long assetId,
        Long userId,
        String actorName,
        String details,
        LocalDateTime createdAt,
        Integer assetVersion
) {}
