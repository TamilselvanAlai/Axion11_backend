package com.axion11.visualops.repository;

import com.axion11.visualops.models.FaceGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface FaceGroupRepository extends JpaRepository<FaceGroup, Long> {
    List<FaceGroup> findByProjectId(Long projectId);
    List<FaceGroup> findByProjectIsNull();
    List<FaceGroup> findByGroupLabel(String groupLabel);

    @Modifying
    @Transactional
    @Query("DELETE FROM FaceGroup g WHERE g.groupLabel = :label")
    void deleteByGroupLabel(@Param("label") String label);
}
