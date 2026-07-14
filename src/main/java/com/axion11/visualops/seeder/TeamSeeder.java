package com.axion11.visualops.seeder;

import com.axion11.visualops.models.Role;
import com.axion11.visualops.models.Team;
import com.axion11.visualops.models.User;
import java.util.HashSet;
import java.util.Set;
import com.axion11.visualops.repository.TeamRepository;
import com.axion11.visualops.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.seed.demo", havingValue = "true")
@Order(1)
@RequiredArgsConstructor
public class TeamSeeder implements CommandLineRunner {

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        seedTeams();
        seedTeamMembers();
    }

    private void seedTeams() {
        String[] teamNames = {
            "Billing Management",
            "Creative Lead",
            "Content Management Team",
            "Design Team",
            "Editing Team",
            "Retouching Team",
            "Quality Control Team"
        };

        for (String name : teamNames) {
            if (!teamRepository.existsByTeamName(name)) {
                teamRepository.save(Team.builder().teamName(name).build());
            }
        }
        System.out.println("✅ Teams seeded: " + teamNames.length + " teams.");
    }

    private void seedTeamMembers() {
        // Design Team
        createUser("sarahlin", "Sarah Lin", Role.DESIGNER, "Design Team");
        createUser("davidpark", "David Park", Role.DESIGNER, "Design Team");
        createUser("emmatorres", "Emma Torres", Role.DESIGNER, "Design Team");
        createUser("ryankumar", "Ryan Kumar", Role.DESIGNER, "Design Team");

        // Editing Team
        createUser("mikechen", "Mike Chen", Role.DESIGNER, "Editing Team");
        createUser("lisaanderson", "Lisa Anderson", Role.DESIGNER, "Editing Team");
        createUser("carlosmartinez", "Carlos Martinez", Role.DESIGNER, "Editing Team");
        createUser("ninapatel", "Nina Patel", Role.DESIGNER, "Editing Team");

        // Quality Control Team
        createUser("alexrivera", "Alex Rivera", Role.REVIEWER, "Quality Control Team");
        createUser("oliviabrown", "Olivia Brown", Role.REVIEWER, "Quality Control Team");
        createUser("jameswu", "James Wu", Role.REVIEWER, "Quality Control Team");

        // Retouching Team
        createUser("janedoe", "Jane Doe", Role.DESIGNER, "Retouching Team");
        createUser("marcusjohnson", "Marcus Johnson", Role.DESIGNER, "Retouching Team");
        createUser("sophialee", "Sophia Lee", Role.DESIGNER, "Retouching Team");
        createUser("tylerscott", "Tyler Scott", Role.DESIGNER, "Retouching Team");
        createUser("tomwilson", "Tom Wilson", Role.DESIGNER, "Retouching Team");
        createUser("rachelkim", "Rachel Kim", Role.DESIGNER, "Retouching Team");
        createUser("danielfoster", "Daniel Foster", Role.DESIGNER, "Retouching Team");

        System.out.println("✅ Team members seeded.");
    }

    private void createUser(String email, String name, Role role, String teamName) {
        Team team = teamRepository.findByTeamName(teamName).orElse(null);
        var existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (team != null && !user.getTeams().contains(team)) {
                user.getTeams().add(team);
                userRepository.save(user);
            }
        } else {
            Set<Team> teams = new HashSet<>();
            if (team != null) teams.add(team);
            User user = User.builder()
                    .email(email)
                    .password(passwordEncoder.encode("test1234"))
                    .name(name)
                    .role(role)
                    .teams(teams)
                    .build();
            userRepository.save(user);
        }
    }
}
