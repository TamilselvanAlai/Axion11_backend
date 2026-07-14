package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "face_groups")
public class FaceGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. "person_1", "person_2" — stable label across uploads */
    @Column(nullable = false)
    private String groupLabel;

    /** JSON double array: normalized [x1,y1, x2,y2, ...] landmark positions */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String landmarkFingerprint;

    /** GCS public URL of the cropped face thumbnail */
    @Column(length = 1024)
    private String faceThumbnailUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
