package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /**
     * Owning team for this project. Nullable so existing projects survive the schema update; they
     * remain visible only to bypass roles (SUPER_ADMIN, ADMIN, CREATIVE_LEAD) until reassigned.
     * See {@code ProjectAccessService} for the visibility rules.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "project_members", joinColumns = @JoinColumn(name = "project_id"), inverseJoinColumns = @JoinColumn(name = "user_id"))
    private java.util.Set<User> members = new java.util.HashSet<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Batch> batches;

    @Column(columnDefinition = "TEXT")
    private String notesGuidelines;

    @Column(length = 500)
    private String referenceFolderUrl;

    @Column(length = 30)
    private String billingModel; // FTE, PER_IMAGE, PER_HOUR

    @Column(length = 10)
    private String currency;

    private Integer numberOfResources;

    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal pricePerHour;

    @Column(length = 20)
    private String imagePricingType; // FLAT_RATE, CATEGORY, PER_SKU

    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal priceFlatRate;

    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal priceSimple;

    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal priceMedium;

    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal priceComplex;

    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal pricePerSku;

    /** Comma-separated marketplace list, e.g. "AMAZON,SHOPIFY,EBAY,ETSY" */
    @Column(length = 200)
    private String marketplaces;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
