package com.axion11.visualops.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class SubtaskDto {
    private Long id;
    private String title;
    private boolean completed;
    private String owner;
    private LocalDate dueDate;
}
