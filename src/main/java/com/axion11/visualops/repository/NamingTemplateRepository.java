package com.axion11.visualops.repository;

import com.axion11.visualops.models.NamingTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NamingTemplateRepository extends JpaRepository<NamingTemplate, Long> {
}
