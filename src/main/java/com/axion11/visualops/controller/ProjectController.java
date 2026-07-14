package com.axion11.visualops.controller;

import com.axion11.visualops.models.dto.ProjectDto;
import com.axion11.visualops.models.dto.ProjectRequest;
import com.axion11.visualops.service.ProjectService;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class ProjectController {

    private final ProjectService projectService;

    @Value("${gcs.bucket.name:axion11-assets}")
    private String bucketName;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CLIENT')")
    public ResponseEntity<ProjectDto> createProject(
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "notesGuidelines", required = false) String notesGuidelines,
            @RequestParam(value = "billingModel", required = false) String billingModel,
            @RequestParam(value = "currency", required = false) String currency,
            @RequestParam(value = "numberOfResources", required = false) Integer numberOfResources,
            @RequestParam(value = "pricePerHour", required = false) java.math.BigDecimal pricePerHour,
            @RequestParam(value = "imagePricingType", required = false) String imagePricingType,
            @RequestParam(value = "priceFlatRate", required = false) java.math.BigDecimal priceFlatRate,
            @RequestParam(value = "priceSimple", required = false) java.math.BigDecimal priceSimple,
            @RequestParam(value = "priceMedium", required = false) java.math.BigDecimal priceMedium,
            @RequestParam(value = "priceComplex", required = false) java.math.BigDecimal priceComplex,
            @RequestParam(value = "pricePerSku", required = false) java.math.BigDecimal pricePerSku,
            @RequestParam(value = "marketplaces", required = false) List<String> marketplaces,
            @RequestParam(value = "teamId", required = false) Long teamId,
            @RequestParam(value = "referenceFiles", required = false) List<MultipartFile> referenceFiles,
            Authentication authentication) {

        ProjectRequest request = new ProjectRequest();
        request.setName(name);
        request.setDescription(description);
        request.setNotesGuidelines(notesGuidelines);
        request.setBillingModel(billingModel);
        request.setCurrency(currency);
        request.setNumberOfResources(numberOfResources);
        request.setPricePerHour(pricePerHour);
        request.setImagePricingType(imagePricingType);
        request.setPriceFlatRate(priceFlatRate);
        request.setPriceSimple(priceSimple);
        request.setPriceMedium(priceMedium);
        request.setPriceComplex(priceComplex);
        request.setPricePerSku(pricePerSku);
        request.setMarketplaces(marketplaces);
        request.setTeamId(teamId);

        ProjectDto createdProject = projectService.createProject(request, authentication.getName());

        // Upload reference files to GCS under references/{projectName}/
        if (referenceFiles != null && !referenceFiles.isEmpty()) {
            String folderName = "references/" + sanitizeFolderName(name) + "/";
            String folderUrl = "https://storage.googleapis.com/" + bucketName + "/" + folderName;

            try {
                Storage storage = StorageOptions.getDefaultInstance().getService();
                for (MultipartFile file : referenceFiles) {
                    if (file.isEmpty()) continue;
                    String gcsFileName = folderName + file.getOriginalFilename();
                    BlobId blobId = BlobId.of(bucketName, gcsFileName);
                    BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                            .setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                            .build();
                    storage.create(blobInfo, file.getBytes());
                    log.info("Uploaded reference file: {}", gcsFileName);
                }
                projectService.setReferenceFolderUrl(createdProject.getId(), folderUrl);
                createdProject.setReferenceFolderUrl(folderUrl);
            } catch (Exception e) {
                log.error("Failed to upload reference files for project {}: {}", name, e.getMessage(), e);
            }
        }

        return ResponseEntity.ok(createdProject);
    }

    // Keep JSON endpoint for backwards compatibility (no files)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CLIENT')")
    public ResponseEntity<ProjectDto> createProjectJson(
            @Valid @RequestBody ProjectRequest request,
            Authentication authentication) {
        ProjectDto createdProject = projectService.createProject(request, authentication.getName());
        return ResponseEntity.ok(createdProject);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CLIENT')")
    public ResponseEntity<ProjectDto> updateProject(
            @PathVariable("id") Long id,
            @Valid @RequestBody ProjectRequest request) {
        ProjectDto updatedProject = projectService.updateProject(id, request);
        return ResponseEntity.ok(updatedProject);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CLIENT')")
    public ResponseEntity<?> deleteProject(@PathVariable("id") Long id) {
        try {
            projectService.deleteProject(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Failed to delete project {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(java.util.Map.of("error", e.getMessage() == null ? "delete failed" : e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectDto> getProject(@PathVariable("id") Long id) {
        return ResponseEntity.ok(projectService.getProjectById(id));
    }

    @GetMapping
    public ResponseEntity<List<ProjectDto>> getProjects(Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_SUPER_ADMIN"));

        if (isAdmin) {
            return ResponseEntity.ok(projectService.getAllProjects());
        } else {
            return ResponseEntity.ok(projectService.getProjectsByOwnerEmail(authentication.getName()));
        }
    }

    private String sanitizeFolderName(String name) {
        return name.trim().toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
    }
}
