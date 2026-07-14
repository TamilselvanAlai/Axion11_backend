package com.axion11.visualops.repository;

import com.axion11.visualops.models.SyncedFile;
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
public interface SyncedFileRepository extends JpaRepository<SyncedFile, Long> {
    Optional<SyncedFile> findByCloudConnectionIdAndProviderFileId(Long cloudConnectionId, String providerFileId);

    List<SyncedFile> findByCloudConnectionId(Long cloudConnectionId);

    @Modifying
    @Transactional
    void deleteByCloudConnectionId(Long cloudConnectionId);

    @Modifying
    @Transactional
    @Query("UPDATE SyncedFile s SET s.imageUpload = null, s.status = 'ORPHANED' WHERE s.imageUpload.id IN :ids")
    int orphanByImageUploadIds(@Param("ids") Collection<Long> ids);
}
