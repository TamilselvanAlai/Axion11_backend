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
@Table(
        name = "synced_files",
        uniqueConstraints = @UniqueConstraint(columnNames = {"cloud_connection_id", "provider_file_id"})
)
public class SyncedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_connection_id", nullable = false)
    private CloudConnection cloudConnection;

    @Column(name = "provider_file_id", nullable = false, length = 255)
    private String providerFileId;

    /** Virtual folder path within the source drive, e.g. "Marketing/Spring 2025/hero.jpg". */
    @Column(name = "provider_path", length = 2048)
    private String providerPath;

    /** Provider-reported checksum at last sync (Drive md5 in hex; OneDrive quickXorHash in base64). */
    @Column(name = "provider_checksum", length = 255)
    private String providerChecksum;

    @Column(name = "provider_modified_at")
    private LocalDateTime providerModifiedAt;

    @Column(name = "provider_size_bytes")
    private Long providerSizeBytes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_upload_id")
    private ImageUpload imageUpload;

    /** GCS object md5 (base64) captured immediately after we wrote the copy. */
    @Column(name = "local_checksum_at_sync", length = 255)
    private String localChecksumAtSync;

    /** SYNCED, LOCALLY_MODIFIED, CONFLICT, ORPHANED, ERROR, SKIPPED_UNSUPPORTED, SKIPPED_TOO_LARGE */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 500)
    private String lastErrorMessage;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
