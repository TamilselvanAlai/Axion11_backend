package com.axion11.visualops.service;

import com.axion11.visualops.models.User;
import com.axion11.visualops.models.WorkSession;
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
        workSessionRepository.findByUserIdAndLogoutTimeIsNull(user.getId()).forEach(stale -> {
            stale.setLogoutTime(stale.getLastHeartbeatAt());
            workSessionRepository.save(stale);
        });

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

    /** Called every activity tick (~30s) while the app is open. {@code idle} reflects whether the
     *  client observed system-wide input (mouse/keyboard, anywhere — not just inside this app's
     *  own window, since the user's real activity is usually happening in a 3rd-party editor)
     *  within the idle threshold since the last tick. Only non-idle ticks extend
     *  {@code activeSeconds}; idle ticks still refresh {@code lastHeartbeatAt} so crash/stale-
     *  session recovery keeps working, they just don't count toward active time. */
    @Transactional
    public void heartbeat(User user, boolean idle, long elapsedSeconds) {
        workSessionRepository.findFirstByUserIdAndLogoutTimeIsNullOrderByLoginTimeDesc(user.getId())
                .ifPresent(session -> {
                    session.setLastHeartbeatAt(LocalDateTime.now());
                    if (!idle) {
                        long clamped = Math.max(0, Math.min(elapsedSeconds, MAX_TICK_SECONDS));
                        session.setActiveSeconds(session.getActiveSeconds() + clamped);
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
                .build();
    }

    /** Per-day active-time breakdown for a user across an arbitrary range — backs the weekly/
     *  monthly reports. Grouped by calendar day of {@code loginTime} rather than by individual
     *  session, since a person can log in/out multiple times a day and the report should show
     *  one row per day. */
    @Transactional(readOnly = true)
    public java.util.Map<LocalDate, Long> activeSecondsByDay(User user, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return workSessionRepository.findByUserIdAndLoginTimeBetween(user.getId(), start, end).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        s -> s.getLoginTime().toLocalDate(),
                        java.util.stream.Collectors.summingLong(WorkSession::getActiveSeconds)));
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
}
