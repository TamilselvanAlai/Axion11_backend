package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkSessionSummaryDto {
    private long activeSecondsToday;
    private int assetsEditedToday;
    private long activeSecondsYesterday;
    private int assetsEditedYesterday;
}
