package com.axion11.visualops.controller;

import com.axion11.visualops.controller.dto.BatchDto;
import com.axion11.visualops.controller.dto.BatchRequest;
import com.axion11.visualops.controller.dto.ImageUploadDto;
import com.axion11.visualops.repository.BatchRepository;
import com.axion11.visualops.service.BatchService;
import com.axion11.visualops.service.BatchService.FileData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/batches")
@RequiredArgsConstructor
public class BatchController {

    private final BatchService batchService;
    private final BatchRepository batchRepository;

    /**
     * POST /api/batches
     * Creates a batch and asynchronously uploads images to GCS.
     * Returns the batch immediately; images upload in the background.
     *
     * File bytes are read into memory synchronously before the async handoff,
     * because multipart temp files are deleted when the HTTP request completes.
     */
    /** JSON body variant — used when creating a batch with no file upload (e.g. folder upload flow). */
    @PostMapping(consumes = "application/json")
    public ResponseEntity<BatchDto> createBatchJson(
            @RequestBody BatchRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        BatchDto batch = batchService.createBatch(request, userDetails.getUsername());
        return ResponseEntity.ok(batch);
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<BatchDto> createBatch(
            @RequestParam("name") String name,
            @RequestParam("projectId") Long projectId,
            @RequestParam(value = "eta", required = false) String eta,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestParam(value = "assignedTo", required = false) String assignedTo,
            @RequestParam(value = "parentBatchId", required = false) Long parentBatchId,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal UserDetails userDetails) throws IOException {

        BatchRequest request = new BatchRequest(name, projectId, eta, notes, assignedTo, parentBatchId);
        BatchDto batch = batchService.createBatch(request, userDetails.getUsername());

        // Pre-read files BEFORE the async handoff (multipart temp files are deleted when request ends)
        if (files != null && !files.isEmpty()) {
            List<FileData> fileDataList = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file.getSize() > 50L * 1024 * 1024) {
                    // Large file: save to temp file to avoid OOM
                    java.io.File temp = java.io.File.createTempFile("upload_", "_" + file.getOriginalFilename());
                    file.transferTo(temp);
                    fileDataList.add(new FileData(
                            file.getOriginalFilename(),
                            file.getContentType(),
                            file.getSize(),
                            null,
                            temp
                    ));
                } else {
                    // Small file: read into memory
                    fileDataList.add(new FileData(
                            file.getOriginalFilename(),
                            file.getContentType(),
                            file.getSize(),
                            file.getBytes()
                    ));
                }
            }
            batchService.uploadImagesAsync(batch.id(), fileDataList, userDetails.getUsername());
        }

        return ResponseEntity.ok(batch);
    }

    /**
     * GET /api/batches/{id}
     * Returns batch details including upload status and image list.
     */
    @GetMapping("/{id}")
    public ResponseEntity<BatchDto> getBatch(@PathVariable("id") Long id) {
        return ResponseEntity.ok(batchService.getBatch(id));
    }

    /**
     * GET /api/batches?projectId=...
     * Returns all batches, optionally filtered by project.
     */
    @GetMapping
    public ResponseEntity<List<BatchDto>> getBatches(
            @RequestParam(value = "projectId", required = false) Long projectId) {
        return ResponseEntity.ok(batchService.getBatchesByProject(projectId));
    }

    /**
     * DELETE /api/batches/{id}
     * Deletes a batch and all related images, tags, QC results, and comments.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBatch(@PathVariable("id") Long id) {
        batchService.deleteBatch(id);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/batches/{id}/start-upload?total=N
     * Resets upload counters for a new upload session before chunks are sent.
     */
    @PostMapping("/{id}/start-upload")
    public ResponseEntity<Void> startUpload(
            @PathVariable("id") Long id,
            @RequestParam("total") int total) {
        if (total == 0) {
            // No files to upload — mark as completed immediately
            batchRepository.updateStatuses(id, "COMPLETED", "ACTIVE");
        } else {
            batchRepository.resetUploadProgress(id, total);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/batches/upload/{id}
     * Uploads images to an existing batch asynchronously.
     */
    @PostMapping(value = "/upload/{id}", consumes = "multipart/form-data")
    public ResponseEntity<BatchDto> uploadToBatch(
            @PathVariable("id") Long id,
            @RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal UserDetails userDetails) throws IOException {

        BatchDto batch = batchService.getBatch(id);

        if (files != null && !files.isEmpty()) {
            List<FileData> fileDataList = new ArrayList<>();
            for (MultipartFile file : files) {
                fileDataList.add(new FileData(
                        file.getOriginalFilename(),
                        file.getContentType(),
                        file.getSize(),
                        file.getBytes()
                ));
            }
            batchService.uploadImagesAsync(batch.id(), fileDataList, userDetails.getUsername());
        }

        return ResponseEntity.ok(batch);
    }

    /**
     * POST /api/batches/{id}/upload-sync
     * Synchronous single-file upload — used by the desktop app's edit-and-resync flow, which
     * needs the newly created version's id back immediately. The async /upload/{id} endpoint
     * above can't provide that: its processing happens on a background thread, so its response
     * returns before the row exists.
     */
    @PostMapping(value = "/{id}/upload-sync", consumes = "multipart/form-data")
    public ResponseEntity<ImageUploadDto> uploadToBatchSync(
            @PathVariable("id") Long id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetails userDetails) throws IOException {
        return ResponseEntity.ok(batchService.uploadSingleImageSync(id, file, userDetails.getUsername()));
    }

    /**
     * PATCH /api/batches/{id}/rename
     * Renames a batch.
     */
    @PatchMapping("/{id}/rename")
    public ResponseEntity<BatchDto> renameBatch(
            @PathVariable("id") Long id,
            @RequestBody java.util.Map<String, String> body) {
        String newName = body.get("name");
        if (newName == null || newName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(batchService.renameBatch(id, newName.trim()));
    }

    /**
     * PATCH /api/batches/{id}/details
     * Updates due date and/or priority. Allowed for SUPER_ADMIN, ADMIN, PROJECT_MANAGER.
     */
    @PatchMapping("/{id}/details")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'PROJECT_MANAGER')")
    public ResponseEntity<BatchDto> updateBatchDetails(
            @PathVariable("id") Long id,
            @RequestBody java.util.Map<String, String> body) {
        String dueDate = body.get("dueDate");
        String priority = body.get("priority");
        return ResponseEntity.ok(batchService.updateBatchDetails(id, dueDate, priority));
    }

    /**
     * PATCH /api/batches/{id}/assign
     * Assigns a batch to a team by team ID.
     */
    @PatchMapping("/{id}/assign")
    public ResponseEntity<BatchDto> assignBatch(
            @PathVariable("id") Long id,
            @RequestBody java.util.Map<String, Object> body) {
        Long teamId = body.get("teamId") != null ? Long.valueOf(body.get("teamId").toString()) : null;
        return ResponseEntity.ok(batchService.assignBatch(id, teamId));
    }

    /**
     * PATCH /api/batches/{id}/move
     * Moves a batch under a new parent batch (sub-batch nesting), or to the top level of a
     * different project — pass exactly one of parentBatchId / projectId.
     */
    @PatchMapping("/{id}/move")
    public ResponseEntity<BatchDto> moveBatch(
            @PathVariable("id") Long id,
            @RequestBody java.util.Map<String, Object> body) {
        Long parentBatchId = body.get("parentBatchId") != null ? Long.valueOf(body.get("parentBatchId").toString()) : null;
        Long projectId = body.get("projectId") != null ? Long.valueOf(body.get("projectId").toString()) : null;
        return ResponseEntity.ok(batchService.moveBatch(id, parentBatchId, projectId));
    }

    /**
     * PATCH /api/batches/assign-bulk
     * Assigns multiple batches/sub-batches to a team in one call (e.g. from a multi-select toolbar).
     */
    @PatchMapping("/assign-bulk")
    public ResponseEntity<List<BatchDto>> assignBatchesBulk(@RequestBody java.util.Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("batchIds");
        Long teamId = body.get("teamId") != null ? Long.valueOf(body.get("teamId").toString()) : null;
        List<BatchDto> results = new ArrayList<>();
        if (rawIds != null) {
            for (Object rawId : rawIds) {
                results.add(batchService.assignBatch(Long.valueOf(rawId.toString()), teamId));
            }
        }
        return ResponseEntity.ok(results);
    }
}
