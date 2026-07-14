package com.axion11.visualops.service;

import com.axion11.visualops.models.Project;
import com.axion11.visualops.models.User;
import com.axion11.visualops.repository.ProjectRepository;
import com.axion11.visualops.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Central authority for project-level visibility (Option A: each project owned by a single team).
 *
 * <p>Visibility rules:
 * <ul>
 *   <li><b>Bypass roles</b> ({@code SUPER_ADMIN}, {@code ADMIN}, {@code CREATIVE_LEAD}) see every
 *   project — they manage across teams.</li>
 *   <li><b>Other authenticated roles</b> see only projects whose {@code project.team_id} matches
 *   their {@link User#getTeam() team}.</li>
 *   <li><b>Projects with no team assigned</b> (legacy or unassigned) are visible only to bypass
 *   roles. This is intentional — assigning a team is required before non-admins can see them.</li>
 *   <li><b>No authenticated user</b> (anonymous, expired token) — sees nothing. Callers should
 *   guard at the controller layer; this service returns an empty allow-set as a safe default.</li>
 * </ul>
 *
 * <p>Used by services that fan out from projects (uploads, batches, search, face groups) to filter
 * results consistently. Frontend role filtering is UX-only — this is the authoritative gate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    /** Roles that bypass team scoping and see every project. */
    private static final Set<String> BYPASS_ROLES = Set.of("SUPER_ADMIN", "ADMIN", "CREATIVE_LEAD");

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    /** Returns the currently authenticated user, or empty if there is no JWT principal. */
    public Optional<User> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        String email = auth.getName();
        if (email == null || email.isBlank() || "anonymousUser".equals(email)) return Optional.empty();
        return userRepository.findByEmail(email);
    }

    /** True when the current user has a role that bypasses team scoping. */
    public boolean currentUserBypassesScope() {
        return currentUser().map(u -> u.getRole() != null && BYPASS_ROLES.contains(u.getRole().name()))
                .orElse(false);
    }

    /**
     * Set of project ids the current user is allowed to see, or {@code null} to mean
     * "no restriction" (i.e. bypass role). Callers can treat null as "skip the filter".
     */
    public Set<Long> allowedProjectIds() {
        if (currentUserBypassesScope()) return null;
        Optional<User> userOpt = currentUser();
        if (userOpt.isEmpty()) return Collections.emptySet();
        User user = userOpt.get();
        if (user.getTeams().isEmpty()) return Collections.emptySet();
        Set<Long> ids = new HashSet<>();
        for (com.axion11.visualops.models.Team t : user.getTeams()) {
            ids.addAll(projectRepository.findIdsByTeamId(t.getId()));
        }
        return ids;
    }

    /**
     * True iff the current user can see the given project. Bypass roles always return true;
     * everyone else needs {@code project.team} to match their team.
     */
    public boolean canAccess(Project project) {
        if (project == null) return false;
        if (currentUserBypassesScope()) return true;
        Optional<User> userOpt = currentUser();
        if (userOpt.isEmpty() || userOpt.get().getTeams().isEmpty()) return false;
        if (project.getTeam() == null) return false;
        Long projectTeamId = project.getTeam().getId();
        return userOpt.get().getTeams().stream().anyMatch(t -> t.getId().equals(projectTeamId));
    }

    /** Variant taking a project id — loads the project once and delegates to {@link #canAccess(Project)}. */
    public boolean canAccess(Long projectId) {
        if (projectId == null) return false;
        return projectRepository.findById(projectId).map(this::canAccess).orElse(false);
    }
}
