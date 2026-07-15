package com.axion11.visualops.repository;

import com.axion11.visualops.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByInviteToken(String inviteToken);

    Optional<User> findByResetToken(String resetToken);

    List<User> findByNameContainingIgnoreCase(String name);

    List<User> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(String name, String email);

    /** Users who belong to the given team (via the user_teams join table). */
    List<User> findByTeamsId(Long teamId);

    /** Users who belong to no team at all. */
    List<User> findByTeamsIsEmpty();

    /** Nulls out the legacy team_id FK column before deleting a team, to avoid a FK violation. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET team_id = NULL WHERE team_id = :teamId", nativeQuery = true)
    void clearLegacyTeamId(@Param("teamId") Long teamId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM user_identities WHERE user_id = :userId", nativeQuery = true)
    void deleteUserIdentities(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM cloud_connections WHERE user_id = :userId", nativeQuery = true)
    void deleteCloudConnections(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE search_audit SET user_id = NULL WHERE user_id = :userId", nativeQuery = true)
    void clearSearchAudits(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE teams SET admin_id = NULL WHERE admin_id = :userId", nativeQuery = true)
    void clearTeamAdmins(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE projects SET owner_id = :newOwnerId WHERE owner_id = :userId", nativeQuery = true)
    void reassignProjectOwnership(@Param("userId") Long userId, @Param("newOwnerId") Long newOwnerId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM project_members WHERE user_id = :userId", nativeQuery = true)
    void removeUserFromProjects(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE tasks SET owner_id = NULL WHERE owner_id = :userId", nativeQuery = true)
    void clearTaskOwners(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE subtasks SET owner_id = NULL WHERE owner_id = :userId", nativeQuery = true)
    void clearSubtaskOwners(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE image_uploads SET uploaded_by_user_id = NULL WHERE uploaded_by_user_id = :userId", nativeQuery = true)
    void clearImageUploadUser(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM user_teams WHERE user_id = :userId", nativeQuery = true)
    void deleteUserTeamsRelationship(@Param("userId") Long userId);
}
