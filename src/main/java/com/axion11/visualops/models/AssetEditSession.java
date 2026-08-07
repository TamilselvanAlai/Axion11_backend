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

    /** Idle-corrected active seconds, accumulated one tick at a time while this session is open
     *  (see AssetEditSessionService#tick) — only ticks where the client reported real system
     *  input within the idle threshold (10 min) get added. This is the authoritative "time
     *  actually spent editing this asset" figure; {@code endedAt - startedAt} is kept only as the
     *  wall-clock open/close range for display, not as the duration itself, since it would
     *  otherwise include however long the user was away from the keyboard mid-session. */
    @Builder.Default
    @Column(nullable = false)
    private Long activeSeconds = 0L;

    @PrePersist
    protected void onCreate() {
        if (startedAt == null) startedAt = LocalDateTime.now();
        if (activeSeconds == null) activeSeconds = 0L;
    }
}
