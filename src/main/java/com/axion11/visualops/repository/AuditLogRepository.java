package com.axion11.visualops.repository;

import com.axion11.visualops.models.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByAssetIdOrderByCreatedAtDesc(Long assetId);
    List<AuditLog> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<AuditLog> findByBatchIdOrderByCreatedAtDesc(Long batchId);
    List<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<AuditLog> findAllByOrderByCreatedAtDesc();

    @Query("SELECT a FROM AuditLog a WHERE a.userId = :userId AND a.eventType IN :eventTypes AND a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<AuditLog> findByUserIdAndEventTypeInAndCreatedAtAfter(@Param("userId") Long userId,
                                                                @Param("eventTypes") List<String> eventTypes,
                                                                @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT a.assetId) FROM AuditLog a WHERE a.createdAt >= :since")
    long countDistinctAssetIdSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT a.assetId) FROM AuditLog a WHERE a.createdAt >= :startDate AND a.createdAt < :endDate")
    long countDistinctAssetIdBetween(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);
}
