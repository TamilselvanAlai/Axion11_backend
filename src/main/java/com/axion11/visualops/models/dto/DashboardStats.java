package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStats {
    private int totalProjects;
    private int totalAssets;
    private int approvedAssets;
    private int pendingReview;
    private int rejectedAssets;
}
