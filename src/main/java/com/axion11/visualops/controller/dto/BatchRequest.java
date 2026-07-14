package com.axion11.visualops.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BatchRequest(
        @NotBlank String name,
        @NotNull Long projectId,
        String eta,
        String notes,
        String assignedTo,
        Long parentBatchId
) {}
