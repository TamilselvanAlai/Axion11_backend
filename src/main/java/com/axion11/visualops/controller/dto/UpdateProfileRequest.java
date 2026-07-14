package com.axion11.visualops.controller.dto;

public record UpdateProfileRequest(
        String name,
        String email,
        String contactNumber,
        String country
) {}
