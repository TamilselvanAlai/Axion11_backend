package com.axion11.visualops.repository;

import com.axion11.visualops.models.MemberPerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MemberPerformanceRepository extends JpaRepository<MemberPerformance, Long> {
    List<MemberPerformance> findAllByOrderByTeamNameAscUserNameAsc();

    @Modifying
    @Query("DELETE FROM MemberPerformance mp")
    void deleteAllRows();
}
