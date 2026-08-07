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
    /** Lifetime active-editing total for this user across every session ever recorded — backs
     *  the dashboard's "total time spent in this app" figure, distinct from today's/yesterday's. */
    private long activeSecondsAllTime;
}
