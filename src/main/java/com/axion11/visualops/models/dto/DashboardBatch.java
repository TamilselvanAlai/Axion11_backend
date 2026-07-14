package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardBatch {
    private String id;
    private String name;
    private int assets;
    private String status;
    private int completion;
    private String assignedTo;
    private String dueDate;
    private String projectId;
    private String projectName;
}
