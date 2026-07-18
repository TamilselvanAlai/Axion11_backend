package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetEditSessionDto {
    private Long assetId;
    private String fileName;
    private String thumbnailUrl;
    private Integer version;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private long durationSeconds;
    private String endReason;
}
