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
@Table(name = "cloud_connections")
public class CloudConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 32)
    private String provider; // GOOGLE_DRIVE, ONEDRIVE

    @Column(length = 4096)
    private String accessToken;

    @Column(length = 4096)
    private String refreshToken;

    private LocalDateTime tokenExpiry;

    @Column(length = 32)
    private String status; // CONNECTED, DISCONNECTED, EXPIRED

    private Long storageUsedBytes;

    private Long totalStorageBytes;

    private Integer totalFileCount;

    private LocalDateTime lastSyncedAt;

    private LocalDateTime connectedAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (connectedAt == null) connectedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
