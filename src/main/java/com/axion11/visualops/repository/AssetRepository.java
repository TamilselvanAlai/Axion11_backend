package com.axion11.visualops.repository;

import com.axion11.visualops.models.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {
    List<Asset> findByBatchId(Long batchId);

    java.util.Optional<Asset> findByExternalId(String externalId);

    @Query("SELECT COUNT(a) FROM Asset a WHERE a.batch.id = :batchId AND LOWER(a.status) = 'approved'")
    int countApprovedByBatchId(@Param("batchId") Long batchId);

    @Query("SELECT COUNT(a) FROM Asset a WHERE a.batch.id = :batchId AND LOWER(a.status) = 'rejected'")
    int countRejectedByBatchId(@Param("batchId") Long batchId);

    @Query("SELECT COUNT(a) FROM Asset a WHERE a.batch.id = :batchId AND (a.status IS NULL OR LOWER(a.status) = 'pending')")
    int countPendingByBatchId(@Param("batchId") Long batchId);
}
