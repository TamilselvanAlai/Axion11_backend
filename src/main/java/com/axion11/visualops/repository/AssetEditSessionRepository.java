package com.axion11.visualops.repository;

import com.axion11.visualops.models.AssetEditSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssetEditSessionRepository extends JpaRepository<AssetEditSession, Long> {
    Optional<AssetEditSession> findFirstByUserIdAndEndedAtIsNullOrderByStartedAtDesc(Long userId);

    List<AssetEditSession> findByUserIdAndStartedAtBetweenAndEndedAtIsNotNullOrderByEndedAtDesc(
            Long userId, LocalDateTime start, LocalDateTime end);

    /** All users' closed sessions in range — backs the weekly/monthly report and payroll rollup,
     *  which (unlike getToday) aren't scoped to a single user by default. */
    List<AssetEditSession> findByStartedAtBetweenAndEndedAtIsNotNull(LocalDateTime start, LocalDateTime end);

    List<AssetEditSession> findByImageUploadIdAndEndedAtIsNotNull(Long imageUploadId);

    List<AssetEditSession> findByImageUploadIdInAndEndedAtIsNotNull(Collection<Long> imageUploadIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM AssetEditSession s WHERE s.imageUpload.id = :imageUploadId")
    void deleteByImageUploadId(@Param("imageUploadId") Long imageUploadId);

    @Modifying
    @Transactional
    @Query("DELETE FROM AssetEditSession s WHERE s.imageUpload.id IN :imageUploadIds")
    void deleteByImageUploadIdIn(@Param("imageUploadIds") Collection<Long> imageUploadIds);
}
