package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "image_qc_results",
       uniqueConstraints = @UniqueConstraint(columnNames = {"image_upload_id", "marketplace"}))
public class ImageQcResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_upload_id", nullable = false)
    private ImageUpload imageUpload;

    /** AMAZON, SHOPIFY, EBAY, ETSY */
    @Column(nullable = false, length = 20)
    private String marketplace;

    /** PASSED, WARNING, REJECTED */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 2000)
    private String details;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime checkedAt = LocalDateTime.now();
}
