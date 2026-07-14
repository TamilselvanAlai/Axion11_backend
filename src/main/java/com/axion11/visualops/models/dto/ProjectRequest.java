package com.axion11.visualops.models.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRequest {
    @NotBlank
    private String name;
    private String description;
    private String notesGuidelines;
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
    private java.util.List<String> memberEmails;
    private java.util.List<String> marketplaces;
    /** Owning team — required for non-admins to see the project. Null leaves it admin-only. */
    private Long teamId;
}
