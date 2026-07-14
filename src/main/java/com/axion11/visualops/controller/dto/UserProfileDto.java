package com.axion11.visualops.controller.dto;

public record UserProfileDto(
        Long id,
        String name,
        String email,
        String contactNumber,
        String country,
        String role,
        String teamName
) {}
