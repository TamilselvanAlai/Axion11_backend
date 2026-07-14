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
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String eventType;  // IMAGE_UPLOAD, IMAGE_TAGGING, TAG_EDIT, TAG_DELETE

    private Long projectId;
    private Long batchId;
    private Long assetId;      // image_upload ID
    private Long userId;

    @Column(length = 255)
    private String details;    // e.g. "Added color:Red", "Changed angle from Front to Back"

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
