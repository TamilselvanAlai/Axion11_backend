package com.axion11.visualops.repository;

import com.axion11.visualops.models.SearchAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SearchAuditRepository extends JpaRepository<SearchAudit, Long> {
    List<SearchAudit> findTop20ByOrderBySearchedAtDesc();
    List<SearchAudit> findTop20ByUserIdOrderBySearchedAtDesc(Long userId);
}
