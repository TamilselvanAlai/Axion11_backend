package com.axion11.visualops.repository;

import com.axion11.visualops.models.WorkSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkSessionRepository extends JpaRepository<WorkSession, Long> {
    List<WorkSession> findByUserIdAndLogoutTimeIsNull(Long userId);

    Optional<WorkSession> findFirstByUserIdAndLogoutTimeIsNullOrderByLoginTimeDesc(Long userId);

    List<WorkSession> findByUserIdAndLoginTimeBetween(Long userId, LocalDateTime start, LocalDateTime end);
}
