package com.axion11.visualops.controller.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class CreateTaskRequest {
    private String title;
    private String status;
    private String ownerEmail;
    private Long projectId;
    private Long batchId;
    private LocalDate startDate;
    private LocalDate dueDate;
    private List<Long> dependencies;
    private Integer linkedAssets;
    private String description;
    private String taskType;
    private String priority;
    private List<SubtaskRequest> subtasks;

    @Data
    public static class SubtaskRequest {
        private String title;
        private boolean completed;
        private String ownerEmail;
        private LocalDate dueDate;
    }
}
