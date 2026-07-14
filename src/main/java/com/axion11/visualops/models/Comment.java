package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(nullable = false)
    private String authorName;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    private boolean resolved = false;

    /** Feedback category: PHOTOGRAPHY_SOURCE, POST_PRODUCTION_PROCESS, POST_PRODUCTION_RETOUCHING, PRODUCTION_MANAGEMENT */
    @Column(length = 50)
    private String feedbackCategory;

    /** Feedback subcategory e.g. Dimension, Resolution, Skin, Delay - Assignment, etc. */
    @Column(length = 80)
    private String feedbackSubcategory;

    /** Severity: low, medium, high — derived from keyword confidence */
    @Column(length = 10)
    private String feedbackSeverity;

    /** URL to annotated image snapshot stored in GCS */
    @Column(length = 1000)
    private String annotationImageUrl;

    /** Pixel coordinates (in the image's natural resolution) of the marked area this comment
     *  refers to — set for pin comments and for comments with a drawn annotation. Null for
     *  plain text comments with no spot on the image. */
    private Double markX;
    private Double markY;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_upload_id")
    private ImageUpload imageUpload;
}
