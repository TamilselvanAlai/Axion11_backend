package com.axion11.visualops.repository;

import com.axion11.visualops.models.TeamPerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TeamPerformanceRepository extends JpaRepository<TeamPerformance, Long> {
    List<TeamPerformance> findAllByOrderByTeamNameAsc();

    @Modifying
    @Query("DELETE FROM TeamPerformance tp")
    void deleteAllRows();
}
