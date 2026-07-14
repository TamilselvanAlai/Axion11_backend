package com.axion11.visualops.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

public record NamingTemplateDto(
        Long id,
        String name,
        String sector,
        String skuBase,
        LocalDateTime createdAt,
        List<NamingTemplateViewDto> views
) {}
