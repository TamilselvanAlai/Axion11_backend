package com.axion11.visualops.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ImageUploadDto(
        Long id,
        String fileName,
        String publicUrl,
        String contentType,
        Long fileSize,
        Long projectId,
        Long batchId,
        String uploadedBy,
        LocalDateTime createdAt,
        List<ImageTagDto> tags,
        Integer width,
        Integer height,
        String colorSpace,
        Integer dpiX,
        Integer dpiY,
        String imageQualityQcCheck,
        String imageTitle,
        String altText,
        String description,
        String previewUrl,
        Integer versionNumber,
        Long originalUploadId,
        Long assignedToUserId,
        String assignedToName,
        String approvalStatus
) {}
