package com.axion11.visualops.service;

import com.axion11.visualops.models.User;
import com.axion11.visualops.models.WorkSession;
import com.axion11.visualops.models.dto.WorkSessionSummaryDto;
import com.axion11.visualops.repository.WorkSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkSessionService {

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

    @Transactional
    public void heartbeat(User user) {
        workSessionRepository.findFirstByUserIdAndLogoutTimeIsNullOrderByLoginTimeDesc(user.getId())
                .ifPresent(session -> {
                    session.setLastHeartbeatAt(LocalDateTime.now());
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
                .build();
    }

    private List<WorkSession> sessionsOn(User user, LocalDate day) {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        return workSessionRepository.findByUserIdAndLoginTimeBetween(user.getId(), start, end);
    }

    private long totalActiveSeconds(User user, LocalDate day) {
        return sessionsOn(user, day).stream()
                .mapToLong(session -> {
                    LocalDateTime end = session.getLogoutTime() != null ? session.getLogoutTime() : session.getLastHeartbeatAt();
                    return Math.max(Duration.between(session.getLoginTime(), end).getSeconds(), 0);
                })
                .sum();
    }

    private int totalAssetsEdited(User user, LocalDate day) {
        return sessionsOn(user, day).stream().mapToInt(WorkSession::getAssetsEditedCount).sum();
    }
}
