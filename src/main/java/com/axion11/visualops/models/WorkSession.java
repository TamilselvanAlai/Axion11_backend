package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One login-to-logout stretch of desktop-app usage, used to derive the dashboard's
 *  "Active Editing Time" / "Assets Edited Today" cards from real activity instead of
 *  placeholder values. {@code lastHeartbeatAt} lets us recover a sane end time if the app
 *  is closed/crashes without an explicit logout. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "work_sessions")
public class WorkSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDateTime loginTime;

    private LocalDateTime logoutTime;

    @Column(nullable = false)
    private LocalDateTime lastHeartbeatAt;

    @Builder.Default
    @Column(nullable = false)
    private Integer assetsEditedCount = 0;

    /** Idle-corrected active seconds, accumulated one heartbeat tick at a time (see
     *  WorkSessionService#heartbeat) — only ticks where the client reported real system input
     *  within the (10-minute) idle threshold get added. This is what the dashboard's "Active
     *  Editing Time" is derived from — a generous threshold that tolerates longer thinking/
     *  waiting pauses during actual creative work. */
    @Builder.Default
    @Column(nullable = false)
    private Long activeSeconds = 0L;

    /** Same idea as activeSeconds but gated by a much shorter (3-minute) idle threshold — "was
     *  the user at their desk at all", not "were they still mid-task". Backs the dashboard's
     *  "Time In App" figure, which is deliberately stricter than Active Editing Time: a 4-minute
     *  gap with no input counts as still-active editing (under the 10-minute bar) but already
     *  excluded from Time In App (over the 3-minute bar). */
    @Builder.Default
    @Column(nullable = false)
    private Long timeInAppSeconds = 0L;

    @PrePersist
    protected void onCreate() {
        if (loginTime == null) loginTime = LocalDateTime.now();
        if (lastHeartbeatAt == null) lastHeartbeatAt = loginTime;
        if (assetsEditedCount == null) assetsEditedCount = 0;
        if (activeSeconds == null) activeSeconds = 0L;
        if (timeInAppSeconds == null) timeInAppSeconds = 0L;
    }
}
