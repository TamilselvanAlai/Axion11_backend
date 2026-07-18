package com.axion11.visualops.repository;

import com.axion11.visualops.models.AssetEditSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssetEditSessionRepository extends JpaRepository<AssetEditSession, Long> {
    Optional<AssetEditSession> findFirstByUserIdAndEndedAtIsNullOrderByStartedAtDesc(Long userId);

    List<AssetEditSession> findByUserIdAndStartedAtBetweenAndEndedAtIsNotNullOrderByEndedAtDesc(
            Long userId, LocalDateTime start, LocalDateTime end);
}
