package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "image_tags")
public class ImageTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_upload_id", nullable = false)
    private ImageUpload imageUpload;

    /** category: age | gender | color | pattern | face_id */
    @Column(nullable = false)
    private String category;

    @Column(nullable = false, length = 500)
    private String value;

    private Double confidence;
}
