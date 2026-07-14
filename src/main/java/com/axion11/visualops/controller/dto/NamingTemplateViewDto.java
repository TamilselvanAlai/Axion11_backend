package com.axion11.visualops.controller.dto;

public record NamingTemplateViewDto(
        Long id,
        String viewName,
        String existingPattern,
        String targetPattern,
        Integer sortOrder
) {}
