package com.axion11.visualops.controller.dto;

public record ImageTagDto(
        Long id,
        String category,
        String value,
        Double confidence
) {}
