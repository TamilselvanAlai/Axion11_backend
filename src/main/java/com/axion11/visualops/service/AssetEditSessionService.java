package com.axion11.visualops.service;

import com.axion11.visualops.models.AssetEditSession;
import com.axion11.visualops.models.ImageUpload;
import com.axion11.visualops.models.User;
import com.axion11.visualops.models.dto.AssetEditSessionDto;
import com.axion11.visualops.repository.AssetEditSessionRepository;
import com.axion11.visualops.repository.ImageUploadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssetEditSessionService {

    /** Mirrors WorkSessionService.MAX_TICK_SECONDS — bounds how much a single client-reported
     *  tick can add, so a delayed/stale tick can't over-credit a session. */
    private static final long MAX_TICK_SECONDS = 90;

    private final AssetEditSessionRepository assetEditSessionRepository;
    private final ImageUploadRepository imageUploadRepository;

    /** Starts tracking time on this asset for this user. Only one edit session is active per
     *  user at a time — opening a different asset closes out whatever was previously open
     *  (SWITCHED); re-opening the same asset that's already active is a no-op so the clock
     *  doesn't reset. */
    @Transactional
    public void startSession(User user, Long assetId) {
        Optional<AssetEditSession> open = openSessionFor(user);
        if (open.isPresent() && open.get().getImageUpload().getId().equals(assetId)) return;

        open.ifPresent(s -> close(s, "SWITCHED"));

        imageUploadRepository.findById(assetId).ifPresent(upload ->
                assetEditSessionRepository.save(AssetEditSession.builder()
                        .user(user)
                        .imageUpload(upload)
                        .startedAt(LocalDateTime.now())
                        .build()));
    }

    /** Called every activity tick (~30s) alongside WorkSessionService#heartbeat while this user
     *  has an asset edit session open. Only non-idle ticks extend {@code activeSeconds} — this is
     *  what turns the raw open-to-save wall-clock span into an idle-corrected "actually editing"
     *  duration (e.g. open the file, step away for a 40-minute idle stretch, come back and save:
     *  the wall-clock span is 40+ minutes, but activeSeconds only reflects the minutes with real
     *  input). No-ops if the ticking session doesn't match {@code assetId} — e.g. a stale tick
     *  arriving just after a switch/save already moved the open session elsewhere. */
    @Transactional
    public void tick(User user, Long assetId, boolean idle, long elapsedSeconds) {
        if (idle) return;
        openSessionFor(user)
                .filter(s -> s.getImageUpload().getId().equals(assetId))
                .ifPresent(s -> {
                    long clamped = Math.max(0, Math.min(elapsedSeconds, MAX_TICK_SECONDS));
                    s.setActiveSeconds(s.getActiveSeconds() + clamped);
                    assetEditSessionRepository.save(s);
                });
    }

    /** Ends the active session for this asset when its edited version finishes syncing. */
    @Transactional
    public void endSession(User user, Long assetId) {
        openSessionFor(user)
                .filter(s -> s.getImageUpload().getId().equals(assetId))
                .ifPresent(s -> close(s, "SAVED"));
    }

    /** Closes whatever edit session is open for this user, regardless of asset — used when the
     *  work session itself ends (logout/app close) so nothing is left dangling open. */
    @Transactional
    public void closeDangling(User user, String reason) {
        openSessionFor(user).ifPresent(s -> close(s, reason));
    }

    @Transactional(readOnly = true)
    public List<AssetEditSessionDto> getToday(User user) {
        LocalDate today = LocalDate.now();
        return getRange(user, today, today);
    }

    /** Same shape as {@link #getToday} but for an arbitrary inclusive date range — backs the
     *  dashboard's "This Week"/"This Month" tabs on the Assets Edited card (self-scoped, unlike
     *  the admin-only payroll report in {@link #getReport}, which spans every user). */
    @Transactional(readOnly = true)
    public List<AssetEditSessionDto> getRange(User user, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return assetEditSessionRepository
                .findByUserIdAndStartedAtBetweenAndEndedAtIsNotNullOrderByEndedAtDesc(user.getId(), start, end)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /** Total logged editing time across this asset's whole edit history — every user, every
     *  version — the "Production Time" shown in the asset info panel to all users, not just
     *  whoever is currently viewing it. Deliberately sums the entire version lineage rather than
     *  just {@code assetId}'s own row: a save can land on a freshly created version row (see
     *  ImageUploadController's replace-content), so summing only the exact row currently being
     *  viewed would reset "time spent" back to zero right after the save that time was spent
     *  working toward. Existing time from earlier versions and time from future edits on this
     *  same asset both accumulate into the one figure. */
    @Transactional(readOnly = true)
    public long getTotalProductionSeconds(Long assetId) {
        return assetEditSessionRepository.findByImageUploadIdInAndEndedAtIsNotNull(lineageIdsFor(assetId)).stream()
                .mapToLong(AssetEditSession::getActiveSeconds)
                .sum();
    }

    /** Every version-row id sharing an edit lineage with {@code assetId}: the root upload (v1,
     *  which has no originalUploadId) plus every version chained to it. Versions form a star,
     *  not a chain — every non-root version's originalUploadId points straight at the root's id
     *  (see ImageUploadService#resolveVersion) — so one extra lookup for the root's own id
     *  alongside the existing findByOriginalUploadId query covers the whole lineage. */
    private List<Long> lineageIdsFor(Long assetId) {
        return imageUploadRepository.findById(assetId)
                .map(upload -> {
                    Long rootId = upload.getOriginalUploadId() != null ? upload.getOriginalUploadId() : upload.getId();
                    List<Long> ids = new java.util.ArrayList<>();
                    ids.add(rootId);
                    imageUploadRepository.findByOriginalUploadIdOrderByVersionNumberAsc(rootId)
                            .forEach(v -> ids.add(v.getId()));
                    return ids;
                })
                .orElse(List.of(assetId));
    }

    private Optional<AssetEditSession> openSessionFor(User user) {
        return assetEditSessionRepository.findFirstByUserIdAndEndedAtIsNullOrderByStartedAtDesc(user.getId());
    }

    private void close(AssetEditSession session, String reason) {
        session.setEndedAt(LocalDateTime.now());
        session.setEndReason(reason);
        assetEditSessionRepository.save(session);
    }

    private AssetEditSessionDto toDto(AssetEditSession s) {
        ImageUpload upload = s.getImageUpload();
        return AssetEditSessionDto.builder()
                .assetId(upload.getId())
                .fileName(upload.getFileName())
                .thumbnailUrl(upload.getPreviewUrl() != null ? upload.getPreviewUrl() : upload.getPublicUrl())
                .version(upload.getVersionNumber())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .durationSeconds(s.getActiveSeconds())
                .idleSecondsExcluded(idleSecondsExcluded(s))
                .endReason(s.getEndReason())
                .build();
    }

    /** Wall-clock open-to-close span minus the idle-corrected active time — i.e. how much of the
     *  session the user was away from the keyboard, for display ("42m active · 18m idle
     *  excluded"). Floored at 0: a session closed before its first tick landed can otherwise show
     *  a spurious negative from clock/tick timing skew. */
    private long idleSecondsExcluded(AssetEditSession s) {
        if (s.getEndedAt() == null) return 0;
        long wallClock = Math.max(Duration.between(s.getStartedAt(), s.getEndedAt()).getSeconds(), 0);
        return Math.max(wallClock - s.getActiveSeconds(), 0);
    }

    /** Per-asset-session detail rows for the weekly/monthly payroll report — every closed edit
     *  session with a start date in range, optionally narrowed to one user. Powers the "which
     *  asset, how long, who" breakdown; {@link #getPayrollRollup} sums this same data up to a
     *  per-user/per-project total for the pay calculation. */
    @Transactional(readOnly = true)
    public List<com.axion11.visualops.models.dto.AssetEditReportRowDto> getReport(LocalDate from, LocalDate to, Long userId) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return assetEditSessionRepository.findByStartedAtBetweenAndEndedAtIsNotNull(start, end).stream()
                .filter(s -> userId == null || s.getUser().getId().equals(userId))
                .sorted(java.util.Comparator.comparing(AssetEditSession::getStartedAt).reversed())
                .map(this::toReportRow)
                .collect(Collectors.toList());
    }

    /** Sums {@link #getReport} down to one row per (user, project) — the actual payroll input:
     *  active hours logged against a project, multiplied by that project's hourly rate. A user
     *  who worked across several projects in the period gets one row per project rather than a
     *  single blended rate, since rates are set per project ({@link Project#getPricePerHour}),
     *  not per user. */
    @Transactional(readOnly = true)
    public List<com.axion11.visualops.models.dto.PayrollRowDto> getPayrollRollup(LocalDate from, LocalDate to, Long userId) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<AssetEditSession> sessions = assetEditSessionRepository.findByStartedAtBetweenAndEndedAtIsNotNull(start, end).stream()
                .filter(s -> userId == null || s.getUser().getId().equals(userId))
                .filter(s -> s.getImageUpload().getProject() != null)
                .collect(Collectors.toList());

        record Key(Long userId, Long projectId) {}
        java.util.Map<Key, List<AssetEditSession>> grouped = sessions.stream()
                .collect(Collectors.groupingBy(s -> new Key(s.getUser().getId(), s.getImageUpload().getProject().getId())));

        return grouped.entrySet().stream()
                .map(e -> {
                    List<AssetEditSession> group = e.getValue();
                    User user = group.get(0).getUser();
                    com.axion11.visualops.models.Project project = group.get(0).getImageUpload().getProject();
                    long activeSeconds = group.stream().mapToLong(AssetEditSession::getActiveSeconds).sum();
                    java.math.BigDecimal rate = project.getPricePerHour() != null ? project.getPricePerHour() : java.math.BigDecimal.ZERO;
                    java.math.BigDecimal hours = java.math.BigDecimal.valueOf(activeSeconds)
                            .divide(java.math.BigDecimal.valueOf(3600), 4, java.math.RoundingMode.HALF_UP);
                    return com.axion11.visualops.models.dto.PayrollRowDto.builder()
                            .userId(user.getId())
                            .userName(user.getName())
                            .projectId(project.getId())
                            .projectName(project.getName())
                            .activeSeconds(activeSeconds)
                            .ratePerHour(rate)
                            .estimatedPay(hours.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP))
                            .build();
                })
                .sorted(java.util.Comparator.comparing(com.axion11.visualops.models.dto.PayrollRowDto::getUserName))
                .collect(Collectors.toList());
    }

    private com.axion11.visualops.models.dto.AssetEditReportRowDto toReportRow(AssetEditSession s) {
        ImageUpload upload = s.getImageUpload();
        com.axion11.visualops.models.Project project = upload.getProject();
        return com.axion11.visualops.models.dto.AssetEditReportRowDto.builder()
                .userId(s.getUser().getId())
                .userName(s.getUser().getName())
                .assetId(upload.getId())
                .fileName(upload.getFileName())
                .projectId(project != null ? project.getId() : null)
                .projectName(project != null ? project.getName() : null)
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .activeSeconds(s.getActiveSeconds())
                .idleSecondsExcluded(idleSecondsExcluded(s))
                .endReason(s.getEndReason())
                .build();
    }
}
