package com.axion11.visualops.controller;

import com.axion11.visualops.models.Role;
import com.axion11.visualops.models.Team;
import com.axion11.visualops.models.User;
import com.axion11.visualops.repository.TeamRepository;
import com.axion11.visualops.repository.UserRepository;
import com.axion11.visualops.service.InvitationEmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TeamController {

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final InvitationEmailService invitationEmailService;

    private static final java.time.Duration INVITE_TOKEN_TTL = java.time.Duration.ofHours(48);

    // ── GET /api/teams ────────────────────────────────────────────────────────

    @GetMapping("/teams")
    public ResponseEntity<List<Map<String, Object>>> getTeams(
            @RequestParam(value = "q", required = false) String q,
            @AuthenticationPrincipal UserDetails userDetails) {
        User caller = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        boolean isAdmin = caller.getRole() == Role.SUPER_ADMIN || caller.getRole() == Role.ADMIN;

        List<Team> teams = isAdmin
                ? teamRepository.findAll()
                : new ArrayList<>(caller.getTeams());

        String query = q != null ? q.trim().toLowerCase() : "";

        List<Map<String, Object>> result = teams.stream().map(team -> {
            List<User> members = userRepository.findByTeamsId(team.getId());

            List<User> matchedMembers = query.isEmpty()
                    ? members
                    : members.stream().filter(u -> userMatches(u, query)).collect(Collectors.toList());

            boolean teamMatches = query.isEmpty()
                    || team.getTeamName().toLowerCase().contains(query)
                    || !matchedMembers.isEmpty();

            if (!teamMatches) return null;

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", team.getId().toString());
            map.put("name", team.getTeamName());
            map.put("memberCount", members.size());
            map.put("adminName", team.getAdmin() != null ? team.getAdmin().getName() : null);
            map.put("members", (query.isEmpty() ? members : matchedMembers).stream().map(u -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.getId());
                m.put("name", u.getName());
                m.put("email", u.getEmail());
                m.put("role", u.getRole() != null ? u.getRole().name() : null);
                m.put("contact", u.getContactNumber());
                m.put("country", u.getCountry());
                m.put("activated", u.getInviteToken() == null);
                m.put("teams", u.getTeams().stream()
                        .map(Team::getTeamName).collect(Collectors.toList()));
                return m;
            }).collect(Collectors.toList()));
            map.put("createdAt", team.getId());
            return map;
        }).filter(Objects::nonNull).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ── POST /api/teams ───────────────────────────────────────────────────────

    @PostMapping("/teams")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> createTeam(@RequestBody Map<String, String> body) {
        String teamName = body.get("teamName");
        String adminName = body.get("adminName");
        String adminEmail = body.get("adminEmail");
        String adminContact = body.get("adminContact");
        String adminCountry = body.get("adminCountry");

        User admin = userRepository.findByEmail(adminEmail).orElse(null);
        if (admin == null) {
            admin = User.builder()
                    .email(adminEmail)
                    .name(adminName)
                    .password(passwordEncoder.encode("test1234"))
                    .role(Role.ADMIN)
                    .country(adminCountry)
                    .contactNumber(adminContact)
                    .build();
            admin = userRepository.save(admin);
        }

        Team team = Team.builder()
                .teamName(teamName)
                .admin(admin)
                .build();
        team = teamRepository.save(team);

        // Add the admin to the team's membership if not already there
        if (!admin.getTeams().contains(team)) {
            admin.getTeams().add(team);
            userRepository.save(admin);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", team.getId().toString());
        result.put("name", team.getTeamName());
        result.put("adminName", admin.getName());
        result.put("adminEmail", admin.getEmail());

        return ResponseEntity.ok(result);
    }

    // ── GET /api/teams/members ────────────────────────────────────────────────

    @GetMapping("/teams/members")
    public ResponseEntity<List<Map<String, Object>>> getTeamMembers(
            @AuthenticationPrincipal UserDetails userDetails) {
        User caller = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        boolean isAdmin = caller.getRole() == Role.SUPER_ADMIN || caller.getRole() == Role.ADMIN;

        List<User> users = isAdmin
                ? userRepository.findAll()
                : caller.getTeams().stream()
                    .flatMap(t -> userRepository.findByTeamsId(t.getId()).stream())
                    .distinct()
                    .collect(Collectors.toList());

        List<Map<String, Object>> result = users.stream()
                .filter(u -> !u.getTeams().isEmpty())
                .map(user -> {
                    String name = user.getName() != null ? user.getName() : user.getEmail();
                    String initials;
                    if (name.contains(" ")) {
                        String[] parts = name.split(" ");
                        initials = ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
                    } else {
                        initials = name.substring(0, Math.min(2, name.length())).toUpperCase();
                    }
                    String teamNames = user.getTeams().stream()
                            .map(Team::getTeamName).collect(Collectors.joining(", "));
                    return Map.<String, Object>of(
                            "id", user.getId().toString(),
                            "name", name,
                            "role", user.getRole() != null ? user.getRole().name() : "",
                            "avatar", initials,
                            "teamName", teamNames
                    );
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ── POST /api/teams/members ───────────────────────────────────────────────

    @PostMapping("/teams/members")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CLIENT', 'PROJECT_MANAGER')")
    public ResponseEntity<Map<String, Object>> createTeamMember(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails) {
        User caller = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();

        String teamNameStr = body.get("teamName");
        if (caller.getRole() != Role.SUPER_ADMIN) {
            boolean authorized = teamNameStr != null && caller.getTeams().stream()
                    .anyMatch(t -> teamNameStr.contains(t.getTeamName()));
            if (!authorized) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "You can only add members to your own team"));
            }
        }

        String name  = body.get("name");
        String roleStr = body.get("role");
        String contact = body.get("contact");
        String country = body.get("country");
        String email = body.get("email");

        Set<Team> teamsToAssign = parseTeamNames(teamNameStr);

        User user;
        if (email != null && !email.isBlank()) {
            if (userRepository.existsByEmail(email)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email already in use"));
            }

            String inviteToken = UUID.randomUUID().toString();
            user = User.builder()
                    .email(email)
                    .name(name)
                    // Random unguessable placeholder — replaced when the invitee sets their own
                    // password via the /set-password activation flow; never used to log in directly.
                    .password(passwordEncoder.encode("invite-" + UUID.randomUUID()))
                    .role(mapRole(roleStr))
                    .teams(teamsToAssign)
                    .country(country)
                    .contactNumber(contact)
                    .inviteToken(inviteToken)
                    .inviteTokenExpiry(java.time.Instant.now().plus(INVITE_TOKEN_TTL))
                    .build();
            user = userRepository.save(user);
            invitationEmailService.sendInviteEmail(user, inviteToken);
        } else {
            String baseEmail = name.toLowerCase().replaceAll("\\s+", "");
            String generatedEmail = baseEmail;
            int suffix = 2;
            while (userRepository.existsByEmail(generatedEmail)) {
                generatedEmail = baseEmail + suffix++;
            }

            user = User.builder()
                    .email(generatedEmail)
                    .name(name)
                    .password(passwordEncoder.encode("test1234"))
                    .role(mapRole(roleStr))
                    .teams(teamsToAssign)
                    .country(country)
                    .contactNumber(contact)
                    .build();
            user = userRepository.save(user);
        }

        String teamNames = user.getTeams().stream()
                .map(Team::getTeamName).collect(Collectors.joining(", "));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId().toString());
        result.put("name", user.getName());
        result.put("email", user.getEmail());
        result.put("role", user.getRole().name());
        result.put("teamName", teamNames);
        return ResponseEntity.ok(result);
    }

    // ── DELETE /api/teams/{id} ────────────────────────────────────────────────

    @DeleteMapping("/teams/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<Void> deleteTeam(@PathVariable("id") Long id) {
        Team team = teamRepository.findById(id).orElse(null);
        if (team == null) return ResponseEntity.notFound().build();

        // Remove this team from every member's teams set
        List<User> members = userRepository.findByTeamsId(id);
        for (User member : members) {
            member.getTeams().removeIf(t -> t.getId().equals(id));
            userRepository.save(member);
        }

        // Null out the legacy team_id FK column so the FK constraint doesn't block deletion
        userRepository.clearLegacyTeamId(id);

        teamRepository.delete(team);
        return ResponseEntity.noContent().build();
    }

    // ── PUT /api/teams/members/{id} ───────────────────────────────────────────

    @PutMapping("/teams/members/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CLIENT', 'PROJECT_MANAGER')")
    public ResponseEntity<Map<String, Object>> updateTeamMember(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        User caller = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        if (caller.getRole() != Role.SUPER_ADMIN) {
            final Set<Team> userTeams = user.getTeams();
            boolean sharedTeam = caller.getTeams().stream()
                    .anyMatch(t -> userTeams.stream()
                            .anyMatch(ut -> ut.getId().equals(t.getId())));
            if (!sharedTeam) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "You can only edit members of your own team"));
            }
        }

        // addToTeam=true → append teams only, leave role unchanged
        boolean addToTeam = "true".equals(body.get("addToTeam"));

        String newEmail = body.get("email");
        if (newEmail != null && !newEmail.isEmpty() && !newEmail.equals(user.getEmail())) {
            if (userRepository.existsByEmail(newEmail)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email already in use"));
            }
            user.setEmail(newEmail);
        }

        String name = body.get("name");
        if (name != null && !name.isEmpty()) user.setName(name);

        String contact = body.get("contact");
        if (contact != null) user.setContactNumber(contact);

        String country = body.get("country");
        if (country != null) user.setCountry(country);

        String teamNameStr = body.get("teamName");
        if (teamNameStr != null) {
            Set<Team> specified = parseTeamNames(teamNameStr);
            if (addToTeam) {
                // Only add the new teams; never remove existing memberships
                user.getTeams().addAll(specified);
            } else {
                // Full replace (explicit edit flow)
                user.setTeams(specified);
            }
        }

        // Only update role in full-edit mode, not when just adding to a team
        String roleStr = body.get("role");
        if (roleStr != null && !roleStr.isEmpty() && !addToTeam) {
            user.setRole(mapRole(roleStr));
        }

        user = userRepository.save(user);

        String teamNames = user.getTeams().stream()
                .map(Team::getTeamName).collect(Collectors.joining(", "));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId().toString());
        result.put("name", user.getName());
        result.put("email", user.getEmail());
        result.put("role", user.getRole().name());
        result.put("contact", user.getContactNumber());
        result.put("country", user.getCountry());
        result.put("teamName", teamNames);
        return ResponseEntity.ok(result);
    }

    // ── POST /api/teams/members/{id}/resend-invite ───────────────────────────

    @PostMapping("/teams/members/{id}/resend-invite")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> resendInvite(@PathVariable("id") Long id) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        if (user.getInviteToken() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "This user has already activated their account"));
        }

        String inviteToken = UUID.randomUUID().toString();
        user.setInviteToken(inviteToken);
        user.setInviteTokenExpiry(java.time.Instant.now().plus(INVITE_TOKEN_TTL));
        userRepository.save(user);
        invitationEmailService.sendInviteEmail(user, inviteToken);

        return ResponseEntity.ok(Map.of("message", "Invitation resent"));
    }

    // ── POST /api/teams/members/{id}/reset-password ──────────────────────────

    @PostMapping("/teams/members/{id}/reset-password")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> resetMemberPassword(@PathVariable("id") Long id) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        if (user.getInviteToken() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "This member hasn't activated their account yet — use Resend Invite instead"));
        }

        String resetToken = UUID.randomUUID().toString();
        user.setResetToken(resetToken);
        user.setResetTokenExpiry(java.time.Instant.now().plus(java.time.Duration.ofHours(1)));
        userRepository.save(user);
        invitationEmailService.sendPasswordResetEmail(user, resetToken);

        return ResponseEntity.ok(Map.of("message", "Password reset link sent"));
    }

    // ── DELETE /api/teams/members/{id} ────────────────────────────────────────

    @DeleteMapping("/teams/members/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CLIENT', 'PROJECT_MANAGER')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Void> deleteTeamMember(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        User caller = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        if (caller.getRole() != Role.SUPER_ADMIN) {
            boolean sharedTeam = caller.getTeams().stream()
                    .anyMatch(t -> user.getTeams().stream()
                            .anyMatch(ut -> ut.getId().equals(t.getId())));
            if (!sharedTeam) {
                return ResponseEntity.status(403).build();
            }
        }

        // Cascading deletion and cleanup of associations to avoid FK constraint errors
        userRepository.deleteUserIdentities(id);
        userRepository.deleteCloudConnections(id);
        userRepository.clearSearchAudits(id);
        userRepository.clearTeamAdmins(id);
        userRepository.reassignProjectOwnership(id, caller.getId());
        userRepository.removeUserFromProjects(id);
        userRepository.clearTaskOwners(id);
        userRepository.clearSubtaskOwners(id);
        userRepository.clearImageUploadUser(id);
        userRepository.deleteUserTeamsRelationship(id);

        // Delete the user from the database
        userRepository.delete(user);
        return ResponseEntity.noContent().build();
    }

    // ── GET /api/teams/unassigned ─────────────────────────────────────────────

    @GetMapping("/teams/unassigned")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getUnassignedUsers(
            @RequestParam(value = "q", required = false) String q) {
        String query = q != null ? q.trim().toLowerCase() : "";
        List<User> users = userRepository.findByTeamsIsEmpty().stream()
                .filter(u -> query.isEmpty() || userMatches(u, query))
                .collect(Collectors.toList());
        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("name", u.getName());
            m.put("email", u.getEmail());
            m.put("role", u.getRole() != null ? u.getRole().name() : null);
            m.put("contact", u.getContactNumber());
            m.put("country", u.getCountry());
            m.put("activated", u.getInviteToken() == null);
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // ── GET /api/users/search ─────────────────────────────────────────────────

    @GetMapping("/users/search")
    public ResponseEntity<List<Map<String, Object>>> searchUsers(
            @RequestParam("q") String query,
            @AuthenticationPrincipal UserDetails userDetails) {
        User caller = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        boolean isAdmin = caller.getRole() == Role.SUPER_ADMIN || caller.getRole() == Role.ADMIN;

        List<User> allMatches = userRepository
                .findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(query, query);
        List<User> users = isAdmin
                ? allMatches
                : allMatches.stream()
                    .filter(u -> u.getTeams().stream()
                            .anyMatch(t -> caller.getTeams().stream()
                                    .anyMatch(ct -> ct.getId().equals(t.getId()))))
                    .collect(Collectors.toList());

        List<Map<String, Object>> result = users.stream().map(user -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", user.getId());
            map.put("name", user.getName());
            map.put("email", user.getEmail());
            map.put("role", user.getRole() != null ? user.getRole().name() : "");
            map.put("teamName", user.getTeams().stream()
                    .map(Team::getTeamName).collect(Collectors.joining(", ")));
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean userMatches(User u, String query) {
        return (u.getName() != null && u.getName().toLowerCase().contains(query))
                || (u.getEmail() != null && u.getEmail().toLowerCase().contains(query))
                || (u.getRole() != null && u.getRole().name().toLowerCase().contains(query));
    }

    private Set<Team> parseTeamNames(String teamNameStr) {
        if (teamNameStr == null || teamNameStr.isBlank()) return new HashSet<>();
        Set<Team> result = new HashSet<>();
        for (String part : teamNameStr.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                teamRepository.findByTeamName(name).ifPresent(result::add);
            }
        }
        return result;
    }

    private Role mapRole(String roleStr) {
        if (roleStr == null) return Role.GUEST;
        try {
            return Role.valueOf(roleStr);
        } catch (IllegalArgumentException ignored) {}
        switch (roleStr) {
            case "Admin": return Role.ADMIN;
            case "Team Manager": return Role.PROJECT_MANAGER;
            case "Project Manager": return Role.PROJECT_MANAGER;
            case "Billing Manager": return Role.BILLING_MANAGER;
            case "Content Manager": return Role.CONTENT_MANAGER;
            case "Creative Lead": return Role.CREATIVE_LEAD;
            case "Editor": return Role.DESIGNER;
            case "Designer": return Role.DESIGNER;
            case "QC Lead": return Role.REVIEWER;
            case "Reviewer": return Role.REVIEWER;
            case "Client": return Role.CLIENT;
            default: return Role.GUEST;
        }
    }
}
