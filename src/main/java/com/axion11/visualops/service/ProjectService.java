package com.axion11.visualops.service;

import com.axion11.visualops.models.FaceGroup;
import com.axion11.visualops.models.ImageUpload;
import com.axion11.visualops.models.Project;
import com.axion11.visualops.models.Task;
import com.axion11.visualops.models.User;
import com.axion11.visualops.models.dto.ProjectDto;
import com.axion11.visualops.models.dto.ProjectRequest;
import com.axion11.visualops.models.Team;
import com.axion11.visualops.repository.FaceGroupRepository;
import com.axion11.visualops.repository.ImageQcResultRepository;
import com.axion11.visualops.repository.ImageUploadRepository;
import com.axion11.visualops.repository.ProjectRepository;
import com.axion11.visualops.repository.SyncedFileRepository;
import com.axion11.visualops.repository.TaskRepository;
import com.axion11.visualops.repository.TeamRepository;
import com.axion11.visualops.repository.UserRepository;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ImageUploadRepository imageUploadRepository;
    private final ImageQcResultRepository imageQcResultRepository;
    private final SyncedFileRepository syncedFileRepository;
    private final TaskRepository taskRepository;
    private final FaceGroupRepository faceGroupRepository;
    private final TeamRepository teamRepository;
    private final ProjectAccessService projectAccessService;

    @Value("${gcs.bucket.name}")
    private String bucketName;

    public ProjectDto createProject(ProjectRequest request, String ownerEmail) {
        User owner = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new com.axion11.visualops.exception.AuthenticatedUserNotFoundException("User not found: " + ownerEmail));

        Set<User> members = resolveMembers(request.getMemberEmails());
        Team team = request.getTeamId() != null
                ? teamRepository.findById(request.getTeamId())
                        .orElseThrow(() -> new RuntimeException("Team not found: " + request.getTeamId()))
                : null;

        Project project = Project.builder()
                .name(request.getName())
                .description(request.getDescription())
                .notesGuidelines(request.getNotesGuidelines())
                .billingModel(request.getBillingModel())
                .currency(request.getCurrency())
                .numberOfResources(request.getNumberOfResources())
                .pricePerHour(request.getPricePerHour())
                .imagePricingType(request.getImagePricingType())
                .priceFlatRate(request.getPriceFlatRate())
                .priceSimple(request.getPriceSimple())
                .priceMedium(request.getPriceMedium())
                .priceComplex(request.getPriceComplex())
                .pricePerSku(request.getPricePerSku())
                .marketplaces(request.getMarketplaces() != null ? String.join(",", request.getMarketplaces()) : null)
                .owner(owner)
                .team(team)
                .members(members)
                .build();

        project = projectRepository.save(project);
        return mapToDto(project);
    }

    public ProjectDto updateProject(Long id, ProjectRequest request) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setNotesGuidelines(request.getNotesGuidelines());
        project.setBillingModel(request.getBillingModel());
        project.setCurrency(request.getCurrency());
        project.setNumberOfResources(request.getNumberOfResources());
        project.setPricePerHour(request.getPricePerHour());
        project.setImagePricingType(request.getImagePricingType());
        project.setPriceFlatRate(request.getPriceFlatRate());
        project.setPriceSimple(request.getPriceSimple());
        project.setPriceMedium(request.getPriceMedium());
        project.setPriceComplex(request.getPriceComplex());
        project.setPricePerSku(request.getPricePerSku());
        if (request.getMarketplaces() != null) {
            project.setMarketplaces(String.join(",", request.getMarketplaces()));
        }

        if (request.getMemberEmails() != null) {
            project.setMembers(resolveMembers(request.getMemberEmails()));
        }

        if (request.getTeamId() != null) {
            // 0 is the "clear" sentinel; non-zero loads the team. Same partial-update convention used elsewhere.
            if (request.getTeamId() == 0L) {
                project.setTeam(null);
            } else {
                Team team = teamRepository.findById(request.getTeamId())
                        .orElseThrow(() -> new RuntimeException("Team not found: " + request.getTeamId()));
                project.setTeam(team);
            }
        }

        project = projectRepository.save(project);
        return mapToDto(project);
    }

    /** Returns true if the project existed and was deleted, false if it was already gone. Idempotent. */
    @Transactional
    public boolean deleteProject(Long id) {
        Project project = projectRepository.findById(id).orElse(null);
        if (project == null) {
            log.info("deleteProject({}) — already gone, treating as success", id);
            return false;
        }

        List<ImageUpload> uploads = imageUploadRepository.findByProjectIdOrderByCreatedAtDesc(id);
        List<Long> uploadIds = uploads.stream().map(ImageUpload::getId).collect(Collectors.toList());

        // 1) Delete the GCS objects backing these uploads (best-effort).
        if (!uploads.isEmpty()) {
            try {
                Storage storage = StorageOptions.getDefaultInstance().getService();
                for (ImageUpload u : uploads) {
                    if (u.getGcsPath() == null || u.getGcsPath().isEmpty()) continue;
                    try {
                        storage.delete(BlobId.of(bucketName, u.getGcsPath()));
                    } catch (Exception e) {
                        log.warn("Failed to delete GCS object {}: {}", u.getGcsPath(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("GCS cleanup skipped for project {}: {}", id, e.getMessage());
            }
        }

        // 2) Detach SyncedFile rows pointing at these uploads (mark ORPHANED, keep cloud-connection metadata).
        if (!uploadIds.isEmpty()) {
            syncedFileRepository.orphanByImageUploadIds(uploadIds);
            // 3) Delete ImageQcResult rows that reference these uploads (no cascade from ImageUpload).
            imageQcResultRepository.deleteByImageUploadIdIn(uploadIds);
        }

        // 4) Delete tasks for this project (cascades subtasks via Task @OneToMany cascade ALL).
        List<Task> tasks = taskRepository.findByProjectId(id);
        if (!tasks.isEmpty()) taskRepository.deleteAll(tasks);

        // 5) Delete face groups associated with the project.
        List<FaceGroup> faceGroups = faceGroupRepository.findByProjectId(id);
        if (!faceGroups.isEmpty()) faceGroupRepository.deleteAll(faceGroups);

        // 6) Finally delete the project — cascades to batches → image_uploads → comments/tags/assets.
        projectRepository.delete(project);
        log.info("Deleted project {} (uploads={}, tasks={}, faceGroups={})", id, uploads.size(), tasks.size(), faceGroups.size());
        return true;
    }

    public ProjectDto getProjectById(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Project not found"));
        if (!projectAccessService.canAccess(project)) {
            throw new java.util.NoSuchElementException("Project not found: " + id);
        }
        return mapToDto(project);
    }

    public List<ProjectDto> getAllProjects() {
        java.util.Set<Long> allowed = projectAccessService.allowedProjectIds();
        java.util.stream.Stream<Project> stream = projectRepository.findAll().stream();
        if (allowed != null) {
            // Non-bypass user — narrow to projects whose id is in the allow-set.
            stream = stream.filter(p -> allowed.contains(p.getId()));
        }
        return stream.map(this::mapToDto).collect(Collectors.toList());
    }

    public List<ProjectDto> getProjectsByOwnerEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.axion11.visualops.exception.AuthenticatedUserNotFoundException("User not found: " + email));

        // Find projects where user is owner OR user is a member
        return projectRepository.findDistinctByOwnerIdOrMembersId(user.getId(), user.getId()).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    public void setReferenceFolderUrl(Long projectId, String url) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));
        project.setReferenceFolderUrl(url);
        projectRepository.save(project);
    }

    private Set<User> resolveMembers(List<String> memberEmails) {
        Set<User> members = new HashSet<>();
        if (memberEmails != null && !memberEmails.isEmpty()) {
            for (String email : memberEmails) {
                userRepository.findByEmail(email).ifPresent(members::add);
            }
        }
        return members;
    }

    private ProjectDto mapToDto(Project project) {
        List<String> memberEmails = project.getMembers().stream()
                .map(User::getEmail)
                .collect(Collectors.toList());

        return ProjectDto.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .notesGuidelines(project.getNotesGuidelines())
                .referenceFolderUrl(project.getReferenceFolderUrl())
                .billingModel(project.getBillingModel())
                .currency(project.getCurrency())
                .numberOfResources(project.getNumberOfResources())
                .pricePerHour(project.getPricePerHour())
                .imagePricingType(project.getImagePricingType())
                .priceFlatRate(project.getPriceFlatRate())
                .priceSimple(project.getPriceSimple())
                .priceMedium(project.getPriceMedium())
                .priceComplex(project.getPriceComplex())
                .pricePerSku(project.getPricePerSku())
                .marketplaces(project.getMarketplaces() != null && !project.getMarketplaces().isEmpty()
                        ? List.of(project.getMarketplaces().split(",")) : List.of())
                .ownerName(project.getOwner().getName())
                .ownerEmail(project.getOwner().getEmail())
                .memberEmails(memberEmails)
                .teamId(project.getTeam() != null ? project.getTeam().getId() : null)
                .teamName(project.getTeam() != null ? project.getTeam().getTeamName() : null)
                .createdAt(project.getCreatedAt())
                .build();
    }
}
