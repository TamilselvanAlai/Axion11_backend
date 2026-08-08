package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Self-scoped totals for an arbitrary date range — backs the Time Management card's Week/Month
 *  tabs, as opposed to WorkSessionSummaryDto's fixed today/yesterday/all-time shape. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkSessionRangeSummaryDto {
    private long activeSeconds;
    private long timeInAppSeconds;
    private int assetsEditedCount;
}
