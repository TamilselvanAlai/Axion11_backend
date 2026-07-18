package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One continuous stretch of "actively editing this asset" — starts when Open File/Retouch is
 *  clicked and ends when that save re-syncs (endReason SAVED), the user opens a different asset
 *  (SWITCHED), or their {@link WorkSession} ends without a save (SESSION_END). Powers the
 *  dashboard's per-asset "time spent editing" breakdown. Deliberately independent of local
 *  file/cache state — a prefetched or already-downloaded file does not start a session; only an
 *  explicit open does, so download time never gets counted as edit time. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "asset_edit_sessions")
public class AssetEditSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "image_upload_id", nullable = false)
    private ImageUpload imageUpload;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    /** SAVED, SWITCHED, or SESSION_END. Null while the session is still open. */
    @Column(length = 20)
    private String endReason;

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) startedAt = LocalDateTime.now();
    }
}
