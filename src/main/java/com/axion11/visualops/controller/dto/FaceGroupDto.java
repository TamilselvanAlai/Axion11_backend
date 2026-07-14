package com.axion11.visualops.controller.dto;

import java.util.List;

public record FaceGroupDto(
        String faceLabel,
        long occurrences,
        String thumbnailUrl,
        List<Long> imageUploadIds
) {}
