package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One (user, project) rollup row for the payroll report — active hours logged against that
 *  project in the period, multiplied by the project's hourly rate. A user who worked across
 *  several projects gets one row per project rather than a single blended figure. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollRowDto {
    private Long userId;
    private String userName;
    private Long projectId;
    private String projectName;
    private long activeSeconds;
    private BigDecimal ratePerHour;
    private BigDecimal estimatedPay;
}
