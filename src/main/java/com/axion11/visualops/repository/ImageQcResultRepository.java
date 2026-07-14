package com.axion11.visualops.repository;

import com.axion11.visualops.models.ImageQcResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ImageQcResultRepository extends JpaRepository<ImageQcResult, Long> {
    List<ImageQcResult> findByImageUploadId(Long imageUploadId);
    Optional<ImageQcResult> findByImageUploadIdAndMarketplace(Long imageUploadId, String marketplace);

    // Explicit bulk JPQL DELETE (not a derived deleteBy... method) so it executes immediately
    // via executeUpdate() — see ImageTagRepository for why a derived delete here is unsafe when
    // a native SQL delete on image_uploads runs afterwards in the same transaction.
    @Modifying
    @Transactional
    @Query("DELETE FROM ImageQcResult q WHERE q.imageUpload.id = :imageUploadId")
    void deleteByImageUploadId(@Param("imageUploadId") Long imageUploadId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ImageQcResult q WHERE q.imageUpload.id IN :imageUploadIds")
    void deleteByImageUploadIdIn(@Param("imageUploadIds") Collection<Long> imageUploadIds);
}
