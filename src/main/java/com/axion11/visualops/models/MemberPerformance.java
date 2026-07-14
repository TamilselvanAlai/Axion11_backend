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
@Table(name = "member_performance")
public class MemberPerformance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "user_role")
    private String userRole;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "team_name", nullable = false)
    private String teamName;

    private Integer projects;
    private Integer assets;

    /** Average time per asset in seconds */
    private Long avgTimeSeconds;

    /** Total time across all assets in seconds */
    private Long totalTimeSeconds;

    /** Approval percentage, rounded to 2 decimal places */
    private Double approvalPercent;

    /** Total distinct feedback comments from QC team and managers */
    private Integer feedback;

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private LocalDateTime computedAt = LocalDateTime.now();
}
