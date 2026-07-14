package com.axion11.visualops.seeder;

import com.axion11.visualops.models.*;
import com.axion11.visualops.repository.BatchRepository;
import com.axion11.visualops.repository.ImageUploadRepository;
import com.axion11.visualops.repository.ProjectRepository;
import com.axion11.visualops.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "app.seed.demo", havingValue = "true")
@Order(2)
@RequiredArgsConstructor
public class DatabaseSeeder implements CommandLineRunner {

        private final UserRepository userRepository;
        private final ProjectRepository projectRepository;
        private final BatchRepository batchRepository;
        private final ImageUploadRepository imageUploadRepository;
        private final PasswordEncoder passwordEncoder;

        @Override
        public void run(String... args) throws Exception {
                seedUsers();

                if (batchRepository.count() == 0) {
                        seedProjects();
                }
        }

        private void seedUsers() {
                if (!userRepository.existsByEmail("connect")) {
                        User admin = User.builder()
                                        .email("connect")
                                        .password(passwordEncoder.encode("Letmesee11"))
                                        .name("Admin User")
                                        .role(Role.ADMIN)
                                        .build();
                        userRepository.save(admin);
                }
        }

        private void seedProjects() {
                Optional<User> adminOpt = userRepository.findByEmail("connect");
                if (adminOpt.isEmpty()) return;
                User admin = adminOpt.get();

                // PROJECT 1: Spring 2025 Campaign
                Project project1 = projectRepository.save(Project.builder()
                                .name("Spring 2025 Campaign")
                                .description("Main campaign for the Spring 2025 clothing line")
                                .owner(admin).build());

                Batch b1 = batchRepository.save(Batch.builder()
                                .name("Batch 1 - Product Photography").status("In Progress").completion(67)
                                .assignedTo("Mike Chen").dueDate("Dec 15, 2024").project(project1).build());
                seedUpload("1-1-1", "hero-banner-01.jpg", "approved", 92, 3840, 2160, 4404019L, b1, project1, admin, "2024-12-08");
                seedUpload("1-1-2", "hero-banner-02.jpg", "pending", 78, 3840, 2160, 3984588L, b1, project1, admin, "2024-12-09");
                seedUpload("1-1-3", "product-grid-a.jpg", "approved", 88, 2400, 2400, 2202009L, b1, project1, admin, "2024-12-07");
                seedUpload("1-1-4", "product-detail-01.jpg", "approved", 89, 2400, 2400, 3355443L, b1, project1, admin, "2024-12-07");
                seedUpload("1-1-5", "product-detail-02.jpg", "pending", 76, 2400, 2400, 3670016L, b1, project1, admin, "2024-12-08");

                Batch b2 = batchRepository.save(Batch.builder()
                                .name("Batch 2 - Lifestyle Shots").status("QC Review").completion(85)
                                .assignedTo("Sarah Lin").dueDate("Dec 18, 2024").project(project1).build());
                seedUpload("1-2-1", "lifestyle-01.jpg", "pending", 65, 3000, 2000, 5976883L, b2, project1, admin, "2024-12-09");
                seedUpload("1-2-2", "lifestyle-02.jpg", "rejected", 42, 3000, 2000, 6396313L, b2, project1, admin, "2024-12-06");
                seedUpload("1-2-3", "lifestyle-03.jpg", "approved", 81, 3000, 2000, 6186803L, b2, project1, admin, "2024-12-07");

                Batch b3 = batchRepository.save(Batch.builder()
                                .name("Batch 3 - Detail Shots").status("Editing").completion(45)
                                .assignedTo("Alex Rivera").dueDate("Dec 20, 2024").project(project1).build());
                seedUpload("1-3-1", "detail-shot-01.jpg", "approved", 85, 2400, 2400, 3250585L, b3, project1, admin, "2024-12-06");
                seedUpload("1-3-2", "detail-shot-02.jpg", "pending", 72, 2400, 2400, 3040575L, b3, project1, admin, "2024-12-06");
                seedUpload("1-3-3", "detail-shot-03.jpg", "approved", 90, 2400, 2400, 3460595L, b3, project1, admin, "2024-12-06");
                seedUpload("1-3-4", "detail-shot-04.jpg", "rejected", 58, 2400, 2400, 2831155L, b3, project1, admin, "2024-12-06");

                Batch b4 = batchRepository.save(Batch.builder()
                                .name("Batch 4 - Studio Portraits").status("Complete").completion(100)
                                .assignedTo("Mike Chen").dueDate("Dec 10, 2024").project(project1).build());
                seedUpload("1-4-1", "portrait-01.jpg", "approved", 95, 3000, 4000, 6501171L, b4, project1, admin, "2024-12-05");
                seedUpload("1-4-2", "portrait-02.jpg", "approved", 93, 3000, 4000, 6815744L, b4, project1, admin, "2024-12-05");
                seedUpload("1-4-3", "portrait-03.jpg", "approved", 91, 3000, 4000, 6396313L, b4, project1, admin, "2024-12-05");
                seedUpload("1-4-4", "portrait-04.jpg", "approved", 88, 3000, 4000, 6186803L, b4, project1, admin, "2024-12-05");

                Batch b5 = batchRepository.save(Batch.builder()
                                .name("Batch 5 - Environmental").status("Upload").completion(20)
                                .assignedTo("Jamie Foster").dueDate("Dec 22, 2024").project(project1).build());
                seedUpload("1-5-1", "outdoor-scene-01.jpg", "pending", 68, 4000, 3000, 8178892L, b5, project1, admin, "2024-12-10");
                seedUpload("1-5-2", "outdoor-scene-02.jpg", "pending", 55, 4000, 3000, 8493465L, b5, project1, admin, "2024-12-10");
                seedUpload("1-5-3", "outdoor-scene-03.jpg", "rejected", 42, 4000, 3000, 7549747L, b5, project1, admin, "2024-12-10");
                seedUpload("1-5-4", "outdoor-scene-04.jpg", "pending", 61, 4000, 3000, 7864320L, b5, project1, admin, "2024-12-10");

                // PROJECT 2: Holiday Collection
                Project project2 = projectRepository.save(Project.builder()
                                .name("Holiday Collection")
                                .description("Winter and holiday season product photography")
                                .owner(admin).build());

                Batch b6 = batchRepository.save(Batch.builder()
                                .name("Batch 1 - Winter Promos").status("Complete").completion(100)
                                .assignedTo("Mike Chen").dueDate("Nov 30, 2024").project(project2).build());
                seedUpload("2-1-1", "winter-promo-01.jpg", "approved", 95, 4000, 3000, 8703475L, b6, project2, admin, "2024-12-08");
                seedUpload("2-1-2", "winter-promo-02.jpg", "approved", 90, 4000, 3000, 8284045L, b6, project2, admin, "2024-12-08");
                seedUpload("2-1-3", "winter-promo-03.jpg", "approved", 87, 4000, 3000, 7864320L, b6, project2, admin, "2024-12-09");

                Batch b7 = batchRepository.save(Batch.builder()
                                .name("Batch 2 - Accessories").status("QC Review").completion(75)
                                .assignedTo("Sarah Lin").dueDate("Dec 5, 2024").project(project2).build());
                seedUpload("2-2-1", "accessory-01.jpg", "approved", 96, 4000, 4000, 9122905L, b7, project2, admin, "2024-12-07");
                seedUpload("2-2-2", "accessory-02.jpg", "approved", 93, 4000, 4000, 9542335L, b7, project2, admin, "2024-12-07");
                seedUpload("2-2-3", "accessory-03.jpg", "pending", 71, 4000, 4000, 8178892L, b7, project2, admin, "2024-12-07");
                seedUpload("2-2-4", "accessory-04.jpg", "rejected", 48, 4000, 4000, 7234150L, b7, project2, admin, "2024-12-07");

                // PROJECT 3: Video Production 2025
                Project project3 = projectRepository.save(Project.builder()
                                .name("Video Production 2025")
                                .description("Brand video production for campaigns and social media")
                                .owner(admin).build());

                Batch b8 = batchRepository.save(Batch.builder()
                                .name("Batch 1 - Product Demos").status("In Progress").completion(55)
                                .assignedTo("Sarah Lin").dueDate("Jan 20, 2025").project(project3).build());
                seedUpload("3-1-1", "product-demo-01.mp4", "pending", 85, 3840, 2160, 130548531L, b8, project3, admin, "2025-01-15");
                seedUpload("3-1-2", "product-demo-02.mp4", "approved", 92, 3840, 2160, 149208474L, b8, project3, admin, "2025-01-16");
                seedUpload("3-1-3", "product-demo-03.mp4", "approved", 88, 3840, 2160, 142290534L, b8, project3, admin, "2025-01-17");

                Batch b9 = batchRepository.save(Batch.builder()
                                .name("Batch 2 - Brand Story").status("Editing").completion(40)
                                .assignedTo("Jamie Foster").dueDate("Jan 25, 2025").project(project3).build());
                seedUpload("3-2-1", "campaign-01.mp4", "pending", 90, 3840, 2160, 269278986L, b9, project3, admin, "2025-01-18");
                seedUpload("3-2-2", "campaign-02.mp4", "approved", 87, 1080, 1920, 93742489L, b9, project3, admin, "2025-01-19");

                Batch b10 = batchRepository.save(Batch.builder()
                                .name("Batch 3 - Lifestyle Videos").status("QC Review").completion(70)
                                .assignedTo("Mike Chen").dueDate("Jan 30, 2025").project(project3).build());
                seedUpload("3-3-1", "lookbook-01.mp4", "pending", 82, 3840, 2160, 186822860L, b10, project3, admin, "2025-01-20");
                seedUpload("3-3-2", "lookbook-02.mp4", "rejected", 65, 3840, 2160, 173932339L, b10, project3, admin, "2025-01-21");
                seedUpload("3-3-3", "lookbook-03.mp4", "approved", 94, 3840, 2160, 208274227L, b10, project3, admin, "2025-01-22");

                System.out.println("Database seeded: 3 projects, 10 batches, 33 image uploads.");
        }

        private void seedUpload(String externalId, String fileName, String approvalStatus, int aiScore,
                        int width, int height, long fileSize, Batch batch, Project project, User uploadedBy, String dateStr) {
                LocalDateTime createdAt = LocalDateTime.parse(dateStr + "T10:00:00");
                imageUploadRepository.save(ImageUpload.builder()
                                .externalId(externalId)
                                .fileName(fileName)
                                .gcsPath("seed/" + fileName)
                                .publicUrl("seed/" + fileName)
                                .contentType(fileName.endsWith(".mp4") ? "video/mp4" : "image/jpeg")
                                .fileSize(fileSize)
                                .approvalStatus(approvalStatus)
                                .uploadStatus("COMPLETED")
                                .aiScore(aiScore)
                                .width(width)
                                .height(height)
                                .project(project)
                                .batch(batch)
                                .uploadedBy(uploadedBy)
                                .createdAt(createdAt)
                                .build());
        }
}
