package com.axion11.visualops.repository;

import com.axion11.visualops.models.ImageTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

public interface ImageTagRepository extends JpaRepository<ImageTag, Long> {
    List<ImageTag> findByImageUploadId(Long imageUploadId);
    List<ImageTag> findByCategory(String category);

    // Explicit bulk JPQL DELETE (not a derived deleteBy... method) so it executes immediately
    // via executeUpdate() — a derived delete-by method instead loads matching entities and
    // queues entityManager.remove() per row, which Hibernate won't flush before a *native* SQL
    // query run afterwards in the same transaction (it can't tell the native SQL touches this
    // table), letting the FK constraint on image_uploads still see the not-yet-deleted rows.
    @Modifying
    @Transactional
    @Query("DELETE FROM ImageTag t WHERE t.imageUpload.id = :imageUploadId")
    void deleteByImageUploadId(@Param("imageUploadId") Long imageUploadId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ImageTag t WHERE t.imageUpload.id IN :imageUploadIds")
    void deleteByImageUploadIdIn(@Param("imageUploadIds") java.util.Collection<Long> imageUploadIds);

    /**
     * Returns image upload IDs whose tags match the given category (exact, case-insensitive)
     * and value (partial, case-insensitive), optionally scoped to a project.
     */
    @Query("SELECT DISTINCT t.imageUpload.id FROM ImageTag t " +
           "JOIN t.imageUpload u " +
           "WHERE LOWER(t.category) = LOWER(:category) " +
           "AND LOWER(t.value) LIKE LOWER(CONCAT('%', :value, '%')) " +
           "AND (:projectId IS NULL OR u.project.id = :projectId)")
    Set<Long> findUploadIdsByCategoryAndValue(@Param("category") String category,
                                              @Param("value") String value,
                                              @Param("projectId") Long projectId);

    /**
     * Returns distinct face_id values with their occurrence count (number of images).
     * Each row: [faceLabel (String), count (Long)]
     */
    @Query("SELECT t.value, COUNT(DISTINCT t.imageUpload.id) FROM ImageTag t " +
           "WHERE t.category = 'face_id' " +
           "GROUP BY t.value " +
           "ORDER BY COUNT(DISTINCT t.imageUpload.id) DESC")
    List<Object[]> findFaceGroupsWithCounts();

    /**
     * Returns the first image upload that has this face_id tag (used as representative thumbnail).
     */
    @Query("SELECT t.imageUpload.id FROM ImageTag t " +
           "WHERE t.category = 'face_id' AND t.value = :faceLabel " +
           "ORDER BY t.imageUpload.createdAt ASC")
    List<Long> findUploadIdsByFaceLabel(@Param("faceLabel") String faceLabel);

    /**
     * Removes face_id=:source tags for the given image upload IDs. Used during a face-group merge
     * to drop the source label from images that already carry the target label, before relabeling
     * the rest. Two-step workflow (find overlap → delete by IDs) avoids MySQL error 1093, which
     * forbids referencing the target table in a subquery of the same DELETE.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ImageTag t WHERE t.category = 'face_id' AND t.value = :source " +
           "AND t.imageUpload.id IN :imageIds")
    void deleteFaceTagsByLabelAndImageIds(@Param("source") String source,
                                          @Param("imageIds") java.util.Collection<Long> imageIds);

    /**
     * Relabels all face_id=:source tags to face_id=:target. Returns rows affected.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ImageTag t SET t.value = :target " +
           "WHERE t.category = 'face_id' AND t.value = :source")
    int relabelFaceTags(@Param("source") String source, @Param("target") String target);
}
