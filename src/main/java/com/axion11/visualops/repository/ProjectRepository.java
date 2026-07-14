package com.axion11.visualops.repository;

import com.axion11.visualops.models.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwnerId(Long ownerId);

    // Find projects where user is owner OR user is in the members set
    List<Project> findDistinctByOwnerIdOrMembersId(Long ownerId, Long memberId);

    /** Projects owned by a team (used for team-scoped access enforcement). */
    List<Project> findByTeamId(Long teamId);

    /** Returns just the project ids owned by a team — used to filter downstream queries. */
    @org.springframework.data.jpa.repository.Query("SELECT p.id FROM Project p WHERE p.team.id = :teamId")
    java.util.Set<Long> findIdsByTeamId(@org.springframework.data.repository.query.Param("teamId") Long teamId);
}
