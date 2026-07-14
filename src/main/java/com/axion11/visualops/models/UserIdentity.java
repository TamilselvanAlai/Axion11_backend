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
        name = "user_identities",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id"})
)
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** GOOGLE, APPLE, X */
    @Column(nullable = false, length = 32)
    private String provider;

    /** Stable identifier from the provider (Google's "sub", Apple's "sub", X's user id). */
    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(length = 255)
    private String email;

    @Column(length = 255)
    private String displayName;

    @Column(length = 1024)
    private String pictureUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
