package com.axion11.visualops.repository;

import com.axion11.visualops.models.Task;
import com.axion11.visualops.models.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByProjectId(Long projectId);
    List<Task> findByProjectIdAndStatus(Long projectId, TaskStatus status);
    List<Task> findByOwnerId(Long ownerId);
}
