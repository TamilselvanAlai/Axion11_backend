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

    @PrePersist
    protected void onCreate() {
        if (loginTime == null) loginTime = LocalDateTime.now();
        if (lastHeartbeatAt == null) lastHeartbeatAt = loginTime;
        if (assetsEditedCount == null) assetsEditedCount = 0;
    }
}
