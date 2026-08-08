package com.axion11.visualops.service;

import com.axion11.visualops.models.User;
import com.axion11.visualops.models.WorkSession;
import com.axion11.visualops.models.dto.WorkSessionRangeSummaryDto;
import com.axion11.visualops.models.dto.WorkSessionSummaryDto;
import com.axion11.visualops.repository.WorkSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkSessionService {

    /** A tick's elapsed-seconds value is client-reported (see useWorkSessionTracking's 30s
     *  activity tick) — clamp what actually gets added server-side so a suspended/sleeping
     *  laptop waking up and sending one delayed tick can't retroactively credit hours of
     *  "active" time it never actually observed input for. */
    private static final long MAX_TICK_SECONDS = 90;

    private final WorkSessionRepository workSessionRepository;
    private final AssetEditSessionService assetEditSessionService;

    @Transactional
    public WorkSession startSession(User user) {
        // Recover from a prior session that never got an explicit logout (crash / force quit) —
        // close it using its last heartbeat instead of letting it "leak" open forever.
        boolean recoveredStaleSession = false;
        List<WorkSession> danglingSessions = workSessionRepository.findByUserIdAndLogoutTimeIsNull(user.getId());
        for (WorkSession stale : danglingSessions) {
            stale.setLogoutTime(stale.getLastHeartbeatAt());
            workSessionRepository.save(stale);
            recoveredStaleSession = true;
        }
        // A crash/force-quit leaves the asset edit session that was open at the time dangling too
        // (endedAt still null) — and getTotalProductionSeconds only sums *closed* sessions, so
        // its already-accumulated activeSeconds sits invisible in Production Time until this
        // closes. Worse, re-opening that same asset later is a no-op in
        // AssetEditSessionService#startSession ("already open for this asset"), so without this
        // it would silently keep ticking into the same never-closing row forever instead of ever
        // being counted. Only worth doing when a WorkSession actually needed recovering — a clean
        // login has nothing dangling to close.
        if (recoveredStaleSession) {
            assetEditSessionService.closeDangling(user, "SESSION_END");
        }

        LocalDateTime now = LocalDateTime.now();
        WorkSession session = WorkSession.builder()
                .user(user)
                .loginTime(now)
                .lastHeartbeatAt(now)
                .assetsEditedCount(0)
                .build();
        return workSessionRepository.save(session);
    }

    @Transactional
    public void endSession(User user) {
        workSessionRepository.findFirstByUserIdAndLogoutTimeIsNullOrderByLoginTimeDesc(user.getId())
                .ifPresent(session -> {
                    session.setLogoutTime(LocalDateTime.now());
                    workSessionRepository.save(session);
                });
        assetEditSessionService.closeDangling(user, "SESSION_END");
    }

    /** Called every activity tick (~30s) while the app is open. Both flags come from the same
     *  client-side idle streak (see useWorkSessionTracking) — how long since real system-wide
     *  input was last observed — just measured against two different bars: {@code idle} is the
     *  10-minute bar backing activeSeconds (generous — tolerates longer pauses mid-task);
     *  {@code idleForApp} is the 3-minute bar backing timeInAppSeconds (stricter — "were they at
     *  their desk at all"). idleForApp therefore flips true first on any sustained gap, so
     *  timeInAppSeconds can be *less* than activeSeconds for the same stretch — that's expected,
     *  not a bug: a 4-minute silent gap still counts as "still working" but not "still present".
     *  Both flags still refresh lastHeartbeatAt regardless, so crash/stale-session recovery keeps
     *  working even through a fully idle stretch. */
    @Transactional
    public void heartbeat(User user, boolean idle, boolean idleForApp, long elapsedSeconds) {
        workSessionRepository.findFirstByUserIdAndLogoutTimeIsNullOrderByLoginTimeDesc(user.getId())
                .ifPresent(session -> {
                    session.setLastHeartbeatAt(LocalDateTime.now());
                    long clamped = Math.max(0, Math.min(elapsedSeconds, MAX_TICK_SECONDS));
                    if (!idle) {
                        session.setActiveSeconds(session.getActiveSeconds() + clamped);
                    }
                    if (!idleForApp) {
                        session.setTimeInAppSeconds(session.getTimeInAppSeconds() + clamped);
                    }
                    workSessionRepository.save(session);
                });
    }

    @Transactional
    public void recordAssetEdit(User user) {
        workSessionRepository.findFirstByUserIdAndLogoutTimeIsNullOrderByLoginTimeDesc(user.getId())
                .ifPresent(session -> {
                    session.setAssetsEditedCount(session.getAssetsEditedCount() + 1);
                    session.setLastHeartbeatAt(LocalDateTime.now());
                    workSessionRepository.save(session);
                });
    }

    @Transactional(readOnly = true)
    public WorkSessionSummaryDto getTodaySummary(User user) {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        return WorkSessionSummaryDto.builder()
                .activeSecondsToday(totalActiveSeconds(user, today))
                .assetsEditedToday(totalAssetsEdited(user, today))
                .activeSecondsYesterday(totalActiveSeconds(user, yesterday))
                .assetsEditedYesterday(totalAssetsEdited(user, yesterday))
                .activeSecondsAllTime(workSessionRepository.sumActiveSecondsByUserId(user.getId()))
                .timeInAppSecondsToday(totalTimeInAppSeconds(user, today))
                .timeInAppSecondsAllTime(workSessionRepository.sumTimeInAppSecondsByUserId(user.getId()))
                .build();
    }

    /** Self-scoped Active Editing Time / Time In App / assets-edited totals for an arbitrary
     *  inclusive date range — backs the Time Management card's Week/Month tabs (mirrors
     *  AssetEditSessionService#getRange for the Assets Edited card). */
    @Transactional(readOnly = true)
    public WorkSessionRangeSummaryDto getRangeSummary(User user, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<WorkSession> sessions = workSessionRepository.findByUserIdAndLoginTimeBetween(user.getId(), start, end);
        return WorkSessionRangeSummaryDto.builder()
                .activeSeconds(sessions.stream().mapToLong(WorkSession::getActiveSeconds).sum())
                .timeInAppSeconds(sessions.stream().mapToLong(WorkSession::getTimeInAppSeconds).sum())
                .assetsEditedCount(sessions.stream().mapToInt(WorkSession::getAssetsEditedCount).sum())
                .build();
    }

    private List<WorkSession> sessionsOn(User user, LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        return workSessionRepository.findByUserIdAndLoginTimeBetween(user.getId(), start, end);
    }

    private long totalActiveSeconds(User user, LocalDate day) {
        return sessionsOn(user, day).stream().mapToLong(WorkSession::getActiveSeconds).sum();
    }

    private int totalAssetsEdited(User user, LocalDate day) {
        return sessionsOn(user, day).stream().mapToInt(WorkSession::getAssetsEditedCount).sum();
    }

    private long totalTimeInAppSeconds(User user, LocalDate day) {
        return sessionsOn(user, day).stream().mapToLong(WorkSession::getTimeInAppSeconds).sum();
    }
}
