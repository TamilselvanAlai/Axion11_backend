package com.axion11.visualops.seeder;

import com.axion11.visualops.models.*;
import com.axion11.visualops.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@ConditionalOnProperty(name = "app.seed.demo", havingValue = "true")
@Order(3)
@RequiredArgsConstructor
public class TaskSeeder implements CommandLineRunner {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    private static final java.util.Map<String, String> TITLE_TO_OWNER = java.util.Map.ofEntries(
        java.util.Map.entry("Product availability",         "sarahchen"),
        java.util.Map.entry("Image selection",              "sarahchen"),
        java.util.Map.entry("Final review",                 "sarahchen"),
        java.util.Map.entry("Video shoot",                  "sarahchen"),
        java.util.Map.entry("Sample readiness",             "mikeross"),
        java.util.Map.entry("Retouching",                   "mikeross"),
        java.util.Map.entry("Color grading",                "mikeross"),
        java.util.Map.entry("Mood board creation",          "mikeross"),
        java.util.Map.entry("Casting & model confirmation", "emmawatson"),
        java.util.Map.entry("Creative brief approval",      "emmawatson"),
        java.util.Map.entry("Product curation",             "emmawatson"),
        java.util.Map.entry("Props sourcing",               "emmawatson"),
        java.util.Map.entry("Photographer booking",         "davidpark"),
        java.util.Map.entry("Location scouting",            "davidpark"),
        java.util.Map.entry("Shot list creation",           "davidpark"),
        java.util.Map.entry("Outdoor shoot prep",           "davidpark"),
        java.util.Map.entry("Studio booking",               "lisakim"),
        java.util.Map.entry("Talent selection",             "lisakim"),
        java.util.Map.entry("Studio session",               "lisakim"),
        java.util.Map.entry("Photography",                  "lisakim"),
        java.util.Map.entry("Shoot day",                    "tomwilson"),
        java.util.Map.entry("Wardrobe preparation",         "tomwilson"),
        java.util.Map.entry("Layout design",                "tomwilson")
    );

    private void patchNullOwners() {
        taskRepository.findAll().forEach(task -> {
            if (task.getOwner() == null && TITLE_TO_OWNER.containsKey(task.getTitle())) {
                userRepository.findByEmail(TITLE_TO_OWNER.get(task.getTitle()))
                        .ifPresent(user -> {
                            task.setOwner(user);
                            taskRepository.save(task);
                        });
            }
        });
    }

    @Override
    public void run(String... args) throws Exception {
        if (taskRepository.count() > 0) {
            patchNullOwners();
            System.out.println("TaskSeeder: Tasks already exist. Checked/patched owners.");
            return;
        }

        // Rename existing projects to match Task Master mock data
        renameProject("Spring 2025 Campaign", "Spring Collection 2024");
        renameProject("Holiday Collection", "Holiday Lookbook");
        renameProject("Video Production 2025", "Winter Campaign");

        // Ensure all 4 projects exist
        Project springCollection = findOrCreateProject("Spring Collection 2024");
        Project winterCampaign   = findOrCreateProject("Winter Campaign");
        Project holidayLookbook  = findOrCreateProject("Holiday Lookbook");
        Project summerEssentials = findOrCreateProject("Summer Essentials");

        // ── Spring Collection 2024 ──────────────────────────────────────────
        save("Product availability",       "completed",    "sarahchen",  springCollection, 2024, 1, 1,  2024, 1, 3,  null,  null, "pre-production", "medium");
        Task sc2 = save("Sample readiness","ready",        "mikeross",   springCollection, 2024, 1, 3,  2024, 1, 5,  null,  null, "pre-production", "medium");
        save("Casting & model confirmation","in-progress", "emmawatson", springCollection, 2024, 1, 5,  2024, 1, 8,  sc2.getId() + "", null, "pre-production", "high");
        save("Photographer booking",        "in-progress", "davidpark",  springCollection, 2024, 1, 6,  2024, 1, 9,  null,  null, "pre-production", "medium");
        save("Studio booking",              "ready",       "lisakim",    springCollection, 2024, 1, 7,  2024, 1, 10, null,  null, "pre-production", "medium");
        save("Shoot day",                   "to-do",       "tomwilson",  springCollection, 2024, 1, 12, 2024, 1, 13, null,  247,  "production",     "high");
        save("Image selection",             "to-do",       "sarahchen",  springCollection, 2024, 1, 15, 2024, 1, 17, null,  null, "post-production","medium");
        save("Retouching",                  "to-do",       "mikeross",   springCollection, 2024, 1, 18, 2024, 1, 22, null,  null, "post-production","medium");

        // ── Winter Campaign ─────────────────────────────────────────────────
        save("Creative brief approval",    "completed",   "emmawatson", winterCampaign, 2024, 1, 2,  2024, 1, 4,  null, null, "pre-production", "high");
        save("Location scouting",          "review",      "davidpark",  winterCampaign, 2024, 1, 5,  2024, 1, 8,  null, null, "pre-production", "medium");
        save("Talent selection",           "in-progress", "lisakim",    winterCampaign, 2024, 1, 8,  2024, 1, 11, null, 89,   "pre-production", "high");
        save("Wardrobe preparation",       "ready",       "tomwilson",  winterCampaign, 2024, 1, 10, 2024, 1, 12, null, null, "production",     "medium");
        save("Video shoot",                "to-do",       "sarahchen",  winterCampaign, 2024, 1, 15, 2024, 1, 16, null, null, "production",     "high");
        save("Color grading",              "to-do",       "mikeross",   winterCampaign, 2024, 1, 18, 2024, 1, 21, null, null, "post-production","medium");

        // ── Holiday Lookbook ────────────────────────────────────────────────
        Task hl1 = save("Product curation","review",      "emmawatson", holidayLookbook, 2024, 1, 1,  2024, 1, 5,  null, null, "pre-production", "medium");
        save("Shot list creation",         "blocked",     "davidpark",  holidayLookbook, 2024, 1, 6,  2024, 1, 9,  hl1.getId() + "", null, "pre-production", "high");
        save("Studio session",             "to-do",       "lisakim",    holidayLookbook, 2024, 1, 13, 2024, 1, 14, null, 156,  "production",     "medium");
        save("Layout design",              "to-do",       "tomwilson",  holidayLookbook, 2024, 1, 16, 2024, 1, 20, null, null, "post-production","medium");
        save("Final review",               "to-do",       "sarahchen",  holidayLookbook, 2024, 1, 22, 2024, 1, 24, null, null, "post-production","low");

        // ── Summer Essentials ───────────────────────────────────────────────
        save("Mood board creation",                   "completed",    "mikeross",    summerEssentials, 2024, 1, 1,  2024, 1, 3,  null,  null,  "pre-production", "low");
        save("Props sourcing",                        "in-progress",  "emmawatson",  summerEssentials, 2024, 1, 4,  2024, 1, 7,  null,  null,  "pre-production", "medium");
        save("Outdoor shoot prep",                    "ready",        "davidpark",   summerEssentials, 2024, 1, 8,  2024, 1, 10, null,  null,  "pre-production", "medium");
        save("Photography",                           "to-do",        "lisakim",     summerEssentials, 2024, 1, 14, 2024, 1, 15, null,  203,   "production",     "high");

        System.out.println("TaskSeeder: Seeded " + taskRepository.count() + " tasks across 4 projects.");
    }

    private Task save(String title, String status, String ownerEmail, Project project,
                      int sy, int sm, int sd, int ey, int em, int ed,
                      String deps, Integer linkedAssets, String taskType, String priority) {

        User owner = userRepository.findByEmail(ownerEmail).orElse(null);

        Task.TaskBuilder builder = Task.builder()
                .title(title)
                .status(parseStatus(status))
                .owner(owner)
                .project(project)
                .startDate(LocalDate.of(sy, sm, sd))
                .dueDate(LocalDate.of(ey, em, ed))
                .dependencies(deps)
                .linkedAssets(linkedAssets)
                .taskType(parseTaskType(taskType))
                .priority(parsePriority(priority));

        return taskRepository.save(builder.build());
    }

    private void renameProject(String oldName, String newName) {
        projectRepository.findAll().stream()
                .filter(p -> p.getName().equals(oldName))
                .findFirst()
                .ifPresent(p -> {
                    p.setName(newName);
                    projectRepository.save(p);
                    System.out.println("TaskSeeder: Renamed project '" + oldName + "' → '" + newName + "'");
                });
    }

    private Project findOrCreateProject(String name) {
        return projectRepository.findAll().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElseGet(() -> {
                    User admin = userRepository.findByEmail("admin")
                            .orElseGet(() -> userRepository.findByEmail("connect").orElseThrow());
                    Project p = Project.builder().name(name).owner(admin).build();
                    Project saved = projectRepository.save(p);
                    System.out.println("TaskSeeder: Created project '" + name + "'");
                    return saved;
                });
    }

    private TaskStatus parseStatus(String s) {
        return switch (s.toLowerCase()) {
            case "ready" -> TaskStatus.READY;
            case "in-progress" -> TaskStatus.IN_PROGRESS;
            case "review" -> TaskStatus.REVIEW;
            case "blocked" -> TaskStatus.BLOCKED;
            case "completed" -> TaskStatus.COMPLETED;
            default -> TaskStatus.TO_DO;
        };
    }

    private TaskType parseTaskType(String s) {
        return switch (s.toLowerCase().replace("-", "_")) {
            case "pre_production" -> TaskType.PRE_PRODUCTION;
            case "post_production" -> TaskType.POST_PRODUCTION;
            default -> TaskType.PRODUCTION;
        };
    }

    private Priority parsePriority(String s) {
        return switch (s.toLowerCase()) {
            case "low" -> Priority.LOW;
            case "high" -> Priority.HIGH;
            default -> Priority.MEDIUM;
        };
    }
}
