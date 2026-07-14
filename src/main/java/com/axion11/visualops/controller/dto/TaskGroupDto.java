package com.axion11.visualops.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TaskGroupDto {
    private Long id;
    private String name;
    private List<TaskDto> tasks;
}
