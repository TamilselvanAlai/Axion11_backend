package com.axion11.visualops.seeder;

import com.axion11.visualops.models.Role;
import com.axion11.visualops.models.User;
import com.axion11.visualops.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.seed.demo", havingValue = "true")
@Order(1)
public class DataSeeder implements CommandLineRunner {

        private final UserRepository userRepository;
        private final PasswordEncoder passwordEncoder;

        public DataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
                this.userRepository = userRepository;
                this.passwordEncoder = passwordEncoder;
        }

        @Override
        public void run(String... args) throws Exception {
                String testPassword = passwordEncoder.encode("test1234");

                // Map of email -> [name, Role]
                Map<String, Object[]> seedData = new LinkedHashMap<>();
                seedData.put("superadmin", new Object[] { "Super Admin", Role.SUPER_ADMIN });
                seedData.put("admin", new Object[] { "Admin User", Role.ADMIN });
                seedData.put("billing", new Object[] { "Billing Manager", Role.BILLING_MANAGER });
                seedData.put("creative", new Object[] { "Creative Lead", Role.CREATIVE_LEAD });
                seedData.put("pm", new Object[] { "Project Manager", Role.PROJECT_MANAGER });
                seedData.put("content", new Object[] { "Content Manager", Role.CONTENT_MANAGER });
                seedData.put("designer", new Object[] { "Designer User", Role.DESIGNER });
                seedData.put("reviewer", new Object[] { "Reviewer User", Role.REVIEWER });
                seedData.put("client", new Object[] { "Client User", Role.CLIENT });
                seedData.put("guest", new Object[] { "Guest User", Role.GUEST });

                // Task Master team members
                seedData.put("sarahchen", new Object[] { "Sarah Chen", Role.DESIGNER });
                seedData.put("mikeross", new Object[] { "Mike Ross", Role.DESIGNER });
                seedData.put("emmawatson", new Object[] { "Emma Watson", Role.CREATIVE_LEAD });
                seedData.put("davidpark", new Object[] { "David Park", Role.DESIGNER });
                seedData.put("lisakim", new Object[] { "Lisa Kim", Role.PROJECT_MANAGER });
                seedData.put("tomwilson", new Object[] { "Tom Wilson", Role.DESIGNER });

                int created = 0;
                for (Map.Entry<String, Object[]> entry : seedData.entrySet()) {
                        String email = entry.getKey();
                        if (!userRepository.existsByEmail(email)) {
                                User user = User.builder()
                                                .email(email)
                                                .name((String) entry.getValue()[0])
                                                .password(testPassword)
                                                .role((Role) entry.getValue()[1])
                                                .build();
                                userRepository.save(user);
                                System.out.println("Created seeded user: " + email + " [" + entry.getValue()[1] + "]");
                                created++;
                        }
                }

                if (created > 0) {
                        System.out.println("DataSeeder: " + created + " role-based users created.");
                } else {
                        System.out.println("DataSeeder: All role-based users already exist. Nothing to seed.");
                }
        }
}
