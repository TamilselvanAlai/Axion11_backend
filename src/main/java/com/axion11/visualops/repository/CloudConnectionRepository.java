package com.axion11.visualops.repository;

import com.axion11.visualops.models.CloudConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CloudConnectionRepository extends JpaRepository<CloudConnection, Long> {
    Optional<CloudConnection> findByUserIdAndProvider(Long userId, String provider);

    List<CloudConnection> findByUserId(Long userId);

    List<CloudConnection> findByStatus(String status);
}
