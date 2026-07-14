package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDto {
    private Long id;
    private String name;
    private String description;
    private String ownerName;
    private String ownerEmail;
    private java.util.List<String> memberEmails;
    private String notesGuidelines;
    private String referenceFolderUrl;
    private String billingModel;
    private String currency;
    private Integer numberOfResources;
    private java.math.BigDecimal pricePerHour;
    private String imagePricingType;
    private java.math.BigDecimal priceFlatRate;
    private java.math.BigDecimal priceSimple;
    private java.math.BigDecimal priceMedium;
    private java.math.BigDecimal priceComplex;
    private java.math.BigDecimal pricePerSku;
    private java.util.List<String> marketplaces;
    /** Owning team id (Option A: 1 project = 1 team). Null = no team assigned, admin-only visibility. */
    private Long teamId;
    private String teamName;
    private LocalDateTime createdAt;
}
