package com.axion11.visualops.service;

import com.axion11.visualops.models.*;
import com.axion11.visualops.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamPerformanceService {

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final BatchRepository batchRepository;
    private final AuditLogRepository auditLogRepository;
    private final CommentRepository commentRepository;
    private final TeamPerformanceRepository teamPerformanceRepository;
    private final MemberPerformanceRepository memberPerformanceRepository;

    public List<TeamPerformance> getAll() {
        return teamPerformanceRepository.findAllByOrderByTeamNameAsc();
    }

    public List<MemberPerformance> getAllMembers() {
        return memberPerformanceRepository.findAllByOrderByTeamNameAscUserNameAsc();
    }

    @Transactional
    public void computeAll() {
        log.info("Starting team performance computation...");
        List<Team> allTeams = teamRepository.findAll();
        List<User> allUsers = userRepository.findAll();
        List<Batch> allBatches = batchRepository.findAll();
        List<AuditLog> allAuditLogs = auditLogRepository.findAllByOrderByCreatedAtDesc();
        List<Comment> allComments = commentRepository.findAll();

        // Group users by team (a user in multiple teams appears under each)
        Map<Long, List<User>> usersByTeam = new HashMap<>();
        for (User u : allUsers) {
            for (com.axion11.visualops.models.Team t : u.getTeams()) {
                usersByTeam.computeIfAbsent(t.getId(), k -> new java.util.ArrayList<>()).add(u);
            }
        }

        // Group batches by team
        Map<Long, List<Batch>> batchesByTeam = allBatches.stream()
                .filter(b -> b.getTeam() != null)
                .collect(Collectors.groupingBy(b -> b.getTeam().getId()));

        // Build set of user IDs per team
        Map<Long, Set<Long>> userIdsByTeam = new HashMap<>();
        usersByTeam.forEach((teamId, users) ->
                userIdsByTeam.put(teamId, users.stream().map(User::getId).collect(Collectors.toSet())));

        // Build set of batch IDs per team
        Map<Long, Set<Long>> batchIdsByTeam = new HashMap<>();
        batchesByTeam.forEach((teamId, batches) ->
                batchIdsByTeam.put(teamId, batches.stream().map(Batch::getId).collect(Collectors.toSet())));

        // Distinct project IDs per team (from batches assigned to the team)
        Map<Long, Set<Long>> projectIdsByTeam = new HashMap<>();
        batchesByTeam.forEach((teamId, batches) ->
                projectIdsByTeam.put(teamId, batches.stream()
                        .map(b -> b.getProject().getId())
                        .collect(Collectors.toSet())));

        // Index audit logs by assetId for quick lookup
        Map<Long, List<AuditLog>> auditByAsset = allAuditLogs.stream()
                .filter(a -> a.getAssetId() != null)
                .collect(Collectors.groupingBy(AuditLog::getAssetId));

        // Collect QC team and manager user IDs (for feedback)
        Set<Long> qcAndManagerUserIds = allUsers.stream()
                .filter(u -> !u.getTeams().isEmpty() && u.getRole() != null)
                .filter(u -> {
                    String teamName = u.getTeams().stream().map(t -> t.getTeamName()).collect(java.util.stream.Collectors.joining(" "));
                    Role role = u.getRole();
                    return teamName.toLowerCase().contains("quality control")
                            || role == Role.PROJECT_MANAGER
                            || role == Role.ADMIN
                            || role == Role.SUPER_ADMIN
                            || role == Role.CREATIVE_LEAD;
                })
                .map(User::getId)
                .collect(Collectors.toSet());
        // Map QC/manager user names for comment matching
        Set<String> qcAndManagerNames = allUsers.stream()
                .filter(u -> qcAndManagerUserIds.contains(u.getId()))
                .map(User::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Index comments by imageUploadId
        Map<Long, List<Comment>> commentsByUploadId = allComments.stream()
                .filter(c -> c.getImageUpload() != null)
                .collect(Collectors.groupingBy(c -> c.getImageUpload().getId()));

        // Build image_upload to batch mapping from audit logs
        Map<Long, Long> assetToBatch = new HashMap<>();
        for (AuditLog a : allAuditLogs) {
            if (a.getAssetId() != null && a.getBatchId() != null) {
                assetToBatch.putIfAbsent(a.getAssetId(), a.getBatchId());
            }
        }

        // Clear old data and compute for each team
        teamPerformanceRepository.deleteAllRows();

        List<TeamPerformance> results = new ArrayList<>();

        for (Team team : allTeams) {
            Long teamId = team.getId();
            Set<Long> teamUserIds = userIdsByTeam.getOrDefault(teamId, Set.of());
            Set<Long> teamBatchIds = batchIdsByTeam.getOrDefault(teamId, Set.of());
            Set<Long> teamProjectIds = projectIdsByTeam.getOrDefault(teamId, Set.of());

            // --- Members ---
            int memberCount = teamUserIds.size();

            // --- Projects ---
            int projectCount = teamProjectIds.size();

            // --- Assets: distinct asset_ids from audit_logs where user belongs to team AND asset belongs to team's batches ---
            Set<Long> teamAssetIds = auditByAsset.entrySet().stream()
                    .filter(entry -> {
                        Long assetId = entry.getKey();
                        Long batchId = assetToBatch.get(assetId);
                        if (batchId == null || !teamBatchIds.contains(batchId)) return false;
                        return entry.getValue().stream().anyMatch(a -> a.getUserId() != null && teamUserIds.contains(a.getUserId()));
                    })
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            int assetCount = teamAssetIds.size();

            // --- Avg Time & Total Time ---
            long totalDiffSeconds = 0;
            int assetsWithTime = 0;
            for (Long assetId : teamAssetIds) {
                List<AuditLog> logs = auditByAsset.getOrDefault(assetId, List.of());
                OptionalLong startOpt = logs.stream()
                        .filter(a -> "ASSIGN_TO_MYSELF".equals(a.getEventType()))
                        .mapToLong(a -> toEpochSecond(a.getCreatedAt()))
                        .min();
                OptionalLong endOpt = logs.stream()
                        .filter(a -> "READY_FOR_PRODUCTION".equals(a.getEventType()))
                        .mapToLong(a -> toEpochSecond(a.getCreatedAt()))
                        .max();
                if (startOpt.isPresent() && endOpt.isPresent()) {
                    long diff = endOpt.getAsLong() - startOpt.getAsLong();
                    if (diff > 0) {
                        totalDiffSeconds += diff;
                        assetsWithTime++;
                    }
                }
            }
            long avgTimeSeconds = assetsWithTime > 0 ? totalDiffSeconds / assetsWithTime : 0;

            // --- Approval % ---
            long approvedAssets = teamAssetIds.stream()
                    .filter(assetId -> {
                        List<AuditLog> logs = auditByAsset.getOrDefault(assetId, List.of());
                        return logs.stream().anyMatch(a -> "READY_FOR_PRODUCTION".equals(a.getEventType()));
                    })
                    .count();
            double approvalPercent = assetCount > 0
                    ? Math.round((double) approvedAssets / assetCount * 10000.0) / 100.0
                    : 0.0;

            // --- Feedback: distinct comments from QC team and managers on team's assets ---
            int feedbackCount = 0;
            for (Long assetId : teamAssetIds) {
                List<Comment> comments = commentsByUploadId.getOrDefault(assetId, List.of());
                feedbackCount += (int) comments.stream()
                        .filter(c -> c.getAuthorName() != null && qcAndManagerNames.contains(c.getAuthorName()))
                        .count();
            }

            TeamPerformance tp = TeamPerformance.builder()
                    .teamId(teamId)
                    .teamName(team.getTeamName())
                    .members(memberCount)
                    .projects(projectCount)
                    .assets(assetCount)
                    .avgTimeSeconds(avgTimeSeconds)
                    .totalTimeSeconds(totalDiffSeconds)
                    .approvalPercent(approvalPercent)
                    .feedback(feedbackCount)
                    .build();
            results.add(tp);
        }

        teamPerformanceRepository.saveAll(results);
        log.info("Team performance computation complete. {} teams processed.", results.size());

        // ── Member Performance ──
        memberPerformanceRepository.deleteAllRows();
        List<MemberPerformance> memberResults = new ArrayList<>();

        for (Team team : allTeams) {
            Long teamId = team.getId();
            Set<Long> teamBatchIds = batchIdsByTeam.getOrDefault(teamId, Set.of());
            Set<Long> teamProjectIds = projectIdsByTeam.getOrDefault(teamId, Set.of());
            List<User> teamUsers = usersByTeam.getOrDefault(teamId, List.of());

            for (User user : teamUsers) {
                Long uid = user.getId();

                // Assets: distinct asset_ids where this user has audit entries AND asset belongs to team's batches
                Set<Long> memberAssetIds = auditByAsset.entrySet().stream()
                        .filter(entry -> {
                            Long batchId = assetToBatch.get(entry.getKey());
                            if (batchId == null || !teamBatchIds.contains(batchId)) return false;
                            return entry.getValue().stream().anyMatch(a -> uid.equals(a.getUserId()));
                        })
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toSet());

                int memberAssetCount = memberAssetIds.size();

                // Avg Time & Total Time
                long memberTotalDiff = 0;
                int memberAssetsWithTime = 0;
                for (Long assetId : memberAssetIds) {
                    List<AuditLog> logs = auditByAsset.getOrDefault(assetId, List.of());
                    OptionalLong startOpt = logs.stream()
                            .filter(a -> "ASSIGN_TO_MYSELF".equals(a.getEventType()))
                            .mapToLong(a -> toEpochSecond(a.getCreatedAt()))
                            .min();
                    OptionalLong endOpt = logs.stream()
                            .filter(a -> "READY_FOR_PRODUCTION".equals(a.getEventType()))
                            .mapToLong(a -> toEpochSecond(a.getCreatedAt()))
                            .max();
                    if (startOpt.isPresent() && endOpt.isPresent()) {
                        long diff = endOpt.getAsLong() - startOpt.getAsLong();
                        if (diff > 0) {
                            memberTotalDiff += diff;
                            memberAssetsWithTime++;
                        }
                    }
                }
                long memberAvgTime = memberAssetsWithTime > 0 ? memberTotalDiff / memberAssetsWithTime : 0;

                // Approval %
                long memberApproved = memberAssetIds.stream()
                        .filter(assetId -> auditByAsset.getOrDefault(assetId, List.of()).stream()
                                .anyMatch(a -> "READY_FOR_PRODUCTION".equals(a.getEventType())))
                        .count();
                double memberApprovalPercent = memberAssetCount > 0
                        ? Math.round((double) memberApproved / memberAssetCount * 10000.0) / 100.0
                        : 0.0;

                // Feedback: comments from QC/managers on this member's assets
                int memberFeedback = 0;
                for (Long assetId : memberAssetIds) {
                    List<Comment> comments = commentsByUploadId.getOrDefault(assetId, List.of());
                    memberFeedback += (int) comments.stream()
                            .filter(c -> c.getAuthorName() != null && qcAndManagerNames.contains(c.getAuthorName()))
                            .count();
                }

                memberResults.add(MemberPerformance.builder()
                        .userId(uid)
                        .userName(user.getName() != null ? user.getName() : user.getEmail())
                        .userRole(user.getRole() != null ? user.getRole().name() : null)
                        .teamId(teamId)
                        .teamName(team.getTeamName())
                        .projects(teamProjectIds.size())
                        .assets(memberAssetCount)
                        .avgTimeSeconds(memberAvgTime)
                        .totalTimeSeconds(memberTotalDiff)
                        .approvalPercent(memberApprovalPercent)
                        .feedback(memberFeedback)
                        .build());
            }
        }

        memberPerformanceRepository.saveAll(memberResults);
        log.info("Member performance computation complete. {} rows processed.", memberResults.size());
    }

    private long toEpochSecond(LocalDateTime dt) {
        return dt.toEpochSecond(java.time.ZoneOffset.UTC);
    }
}
