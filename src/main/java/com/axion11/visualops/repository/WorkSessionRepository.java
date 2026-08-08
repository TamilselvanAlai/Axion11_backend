package com.axion11.visualops.repository;

import com.axion11.visualops.models.WorkSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkSessionRepository extends JpaRepository<WorkSession, Long> {
    List<WorkSession> findByUserIdAndLogoutTimeIsNull(Long userId);

    Optional<WorkSession> findFirstByUserIdAndLogoutTimeIsNullOrderByLoginTimeDesc(Long userId);

    List<WorkSession> findByUserIdAndLoginTimeBetween(Long userId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT COALESCE(SUM(s.activeSeconds), 0) FROM WorkSession s WHERE s.user.id = :userId")
    long sumActiveSecondsByUserId(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(s.timeInAppSeconds), 0) FROM WorkSession s WHERE s.user.id = :userId")
    long sumTimeInAppSecondsByUserId(@Param("userId") Long userId);
}
