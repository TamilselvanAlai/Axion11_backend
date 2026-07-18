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
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        return assetEditSessionRepository
                .findByUserIdAndStartedAtBetweenAndEndedAtIsNotNullOrderByEndedAtDesc(user.getId(), start, end)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
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
        long seconds = Math.max(Duration.between(s.getStartedAt(), s.getEndedAt()).getSeconds(), 0);
        ImageUpload upload = s.getImageUpload();
        return AssetEditSessionDto.builder()
                .assetId(upload.getId())
                .fileName(upload.getFileName())
                .thumbnailUrl(upload.getPreviewUrl() != null ? upload.getPreviewUrl() : upload.getPublicUrl())
                .version(upload.getVersionNumber())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .durationSeconds(seconds)
                .endReason(s.getEndReason())
                .build();
    }
}
