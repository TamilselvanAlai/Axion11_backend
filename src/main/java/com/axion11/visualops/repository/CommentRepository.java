package com.axion11.visualops.repository;

import com.axion11.visualops.models.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByAssetId(Long assetId);
    List<Comment> findByImageUploadId(Long imageUploadId);

    // Explicit bulk JPQL DELETE (not a derived deleteBy... method) so it executes immediately
    // via executeUpdate() — see ImageTagRepository for why a derived delete here is unsafe when
    // a native SQL delete on image_uploads runs afterwards in the same transaction.
    @Modifying
    @Transactional
    @Query("DELETE FROM Comment c WHERE c.imageUpload.id = :imageUploadId")
    void deleteByImageUploadId(@Param("imageUploadId") Long imageUploadId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Comment c WHERE c.imageUpload.id IN :imageUploadIds")
    void deleteByImageUploadIdIn(@Param("imageUploadIds") Collection<Long> imageUploadIds);

    /** Aggregate feedback counts grouped by category, subcategory, severity — across all projects */
    @Query("SELECT c.feedbackCategory, c.feedbackSubcategory, c.feedbackSeverity, COUNT(c) " +
           "FROM Comment c WHERE c.feedbackCategory IS NOT NULL " +
           "GROUP BY c.feedbackCategory, c.feedbackSubcategory, c.feedbackSeverity")
    List<Object[]> countByFeedbackCategoryAll();

    /** Same but filtered by project id */
    @Query("SELECT c.feedbackCategory, c.feedbackSubcategory, c.feedbackSeverity, COUNT(c) " +
           "FROM Comment c LEFT JOIN c.imageUpload iu LEFT JOIN c.asset a LEFT JOIN a.batch ab " +
           "WHERE c.feedbackCategory IS NOT NULL " +
           "AND (iu.project.id = :projectId OR ab.project.id = :projectId) " +
           "GROUP BY c.feedbackCategory, c.feedbackSubcategory, c.feedbackSeverity")
    List<Object[]> countByFeedbackCategoryForProject(@Param("projectId") Long projectId);

    /** Same but filtered by batch id */
    @Query("SELECT c.feedbackCategory, c.feedbackSubcategory, c.feedbackSeverity, COUNT(c) " +
           "FROM Comment c LEFT JOIN c.imageUpload iu LEFT JOIN c.asset a " +
           "WHERE c.feedbackCategory IS NOT NULL " +
           "AND (iu.batch.id = :batchId OR a.batch.id = :batchId) " +
           "GROUP BY c.feedbackCategory, c.feedbackSubcategory, c.feedbackSeverity")
    List<Object[]> countByFeedbackCategoryForBatch(@Param("batchId") Long batchId);
}
