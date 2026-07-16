package com.axion11.visualops.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class TaskDto {
    private Long id;
    private String name;
    private String status;       // to-do, ready, in-progress, review, blocked, completed
    private String owner;        // owner display name
    private String ownerEmail;
    private Long projectId;
    private String projectName;
    private Long batchId;
    private String batchName;
    private LocalDate startDate;
    private LocalDate dueDate;
    private List<String> dependencies;
    private Integer linkedAssets;
    private String description;
    private String taskType;     // pre-production, production, post-production
    private String priority;     // low, medium, high
    private List<SubtaskDto> subtasks;
    private LocalDateTime completedAt;
}
