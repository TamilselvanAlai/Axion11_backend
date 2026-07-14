package com.axion11.visualops.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

public record BatchDto(
        Long id,
        String name,
        Long projectId,
        String projectName,
        Long parentBatchId,
        String status,
        String uploadStatus,
        Integer totalImages,
        Integer uploadedImages,
        String assignedTo,
        Long teamId,
        String teamName,
        String dueDate,
        String priority,
        String notes,
        LocalDateTime createdAt,
        List<ImageUploadDto> imageUploads
) {}
