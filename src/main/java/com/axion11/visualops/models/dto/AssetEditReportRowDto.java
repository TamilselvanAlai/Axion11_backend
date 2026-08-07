package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One row of the weekly/monthly time report — a single closed asset-edit session, with the
 *  user and project attached (unlike AssetEditSessionDto, which is scoped to "my sessions
 *  today" and doesn't need to name the user). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetEditReportRowDto {
    private Long userId;
    private String userName;
    private Long assetId;
    private String fileName;
    private Long projectId;
    private String projectName;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private long activeSeconds;
    private long idleSecondsExcluded;
    private String endReason;
}
