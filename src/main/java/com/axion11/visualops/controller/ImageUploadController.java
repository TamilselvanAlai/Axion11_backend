package com.axion11.visualops.controller;

import com.axion11.visualops.controller.dto.CommentDto;
import com.axion11.visualops.controller.dto.FaceGroupDto;
import com.axion11.visualops.controller.dto.ImageUploadDto;
import com.axion11.visualops.service.ImageQcService;
import com.axion11.visualops.service.ImageUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class ImageUploadController {

    private final ImageUploadService imageUploadService;
    private final ImageQcService imageQcService;

    /**
     * POST /api/uploads/signed-url
     * Generates a GCS signed URL for direct browser-to-GCS upload (bypasses Cloud Run 32MB limit).
     */
    @PostMapping("/signed-url")
    public ResponseEntity<Map<String, String>> getSignedUploadUrl(
            @RequestBody Map<String, String> body) {
        String fileName = body.get("fileName");
        String contentType = body.getOrDefault("contentType", "application/octet-stream");
        Map<String, String> result = imageUploadService.generateSignedUploadUrl(fileName, contentType);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/uploads/confirm
     * Confirms a file uploaded directly to GCS, creates the DB record and triggers AI processing.
     */
    @PostMapping("/confirm")
    public ResponseEntity<ImageUploadDto> confirmUpload(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserDetails userDetails) {
        String gcsFileName = (String) body.get("gcsFileName");
        String originalFileName = (String) body.get("originalFileName");
        String contentType = (String) body.get("contentType");
        long fileSize = ((Number) body.get("fileSize")).longValue();
        Long projectId = body.get("projectId") != null ? ((Number) body.get("projectId")).longValue() : null;
        Long batchId = body.get("batchId") != null ? ((Number) body.get("batchId")).longValue() : null;
        ImageUploadDto result = imageUploadService.confirmDirectUpload(
                gcsFileName, originalFileName, contentType, fileSize, projectId, batchId, userDetails.getUsername());
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/uploads/{id}/replace-content
     * Replaces an existing upload's file in place — same row, same version number — instead of
     * creating a new version. Used by the desktop app's edit-and-resync flow: an editor's save
     * becomes draft + established (VE) on the version they were already working on; the version
     * number itself only advances when QC approves it (see AssetController#approveAsset).
     */
    @PostMapping("/{id}/replace-content")
    public ResponseEntity<ImageUploadDto> replaceContent(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> body) {
        String gcsFileName = (String) body.get("gcsFileName");
        String contentType = (String) body.get("contentType");
        long fileSize = ((Number) body.get("fileSize")).longValue();
        ImageUploadDto result = imageUploadService.replaceContent(id, gcsFileName, contentType, fileSize);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/uploads
     * Multipart form: files[] + optional projectId
     * Uploads images to GCS and runs Vision API tagging.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<List<ImageUploadDto>> upload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<ImageUploadDto> result = imageUploadService.uploadImages(files, projectId, userDetails.getUsername());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/uploads?projectId=...
     * Returns all uploads (optionally filtered by project).
     */
    @GetMapping
    public ResponseEntity<List<ImageUploadDto>> getUploads(
            @RequestParam(value = "projectId", required = false) Long projectId) {
        return ResponseEntity.ok(imageUploadService.getUploads(projectId));
    }

    /**
     * GET /api/uploads/search?tag=color:Blue&tag=gender:female&projectId=1
     *
     * Search images whose tags match ALL supplied criteria (AND semantics).
     * Each {@code tag} param is {@code category:value} — value match is case-insensitive
     * and partial (contains). Supported categories: age, gender, color, pattern, face_id.
     *
     * Example queries:
     *   /api/uploads/search?tag=color:Blue
     *   /api/uploads/search?tag=face_id:person_1&tag=color:Red
     *   /api/uploads/search?tag=gender:female&tag=pattern:Stripes&projectId=3
     */
    @GetMapping("/search")
    public ResponseEntity<List<ImageUploadDto>> search(
            @RequestParam(value = "tag", required = false) List<String> tags,
            @RequestParam(value = "projectId", required = false) Long projectId) {
        return ResponseEntity.ok(imageUploadService.searchByTags(tags, projectId));
    }

    /**
     * GET /api/uploads/search-by-name?projectId=1&q=hero
     *
     * Case-insensitive file-name/keyword search. With projectId, scoped to that project's
     * batches; without it, searches across every project the caller can access. Powers the
     * Assets/DAM toolbar search bar (distinct from the tag-based /search above).
     */
    @GetMapping("/search-by-name")
    public ResponseEntity<List<ImageUploadDto>> searchByName(
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam("q") String query) {
        return ResponseEntity.ok(imageUploadService.searchByFileName(projectId, query));
    }

    /**
     * GET /api/uploads/{id}
     * Returns a single upload with its tags.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ImageUploadDto> getUpload(@PathVariable("id") Long id,
                                                     @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails != null ? userDetails.getUsername() : null;
        return ResponseEntity.ok(imageUploadService.getUpload(id, username));
    }

    /**
     * GET /api/uploads/{id}/versions
     * Returns all versions of a file (v1 first). Works regardless of which version ID is passed.
     */
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<ImageUploadDto>> getVersions(@PathVariable("id") Long id) {
        return ResponseEntity.ok(imageUploadService.getAllVersions(id));
    }

    /**
     * GET /api/uploads/download/{id}
     * Downloads the original image file for editing.
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable("id") Long id,
                                                @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails != null ? userDetails.getUsername() : null;
        return imageUploadService.downloadFile(id, username);
    }

    /**
     * POST /api/uploads/{id}/record-download
     * Logs an ASSET_DOWNLOAD event without transferring the file — used when the client
     * fetched the file directly from its public storage URL (e.g. the desktop app's local
     * open-and-sync flow) rather than through {@link #downloadFile}, so the download still
     * shows up in Transfers.
     */
    @PostMapping("/{id}/record-download")
    public ResponseEntity<Void> recordDownload(@PathVariable("id") Long id,
                                                @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails != null) {
            imageUploadService.recordDownload(id, userDetails.getUsername());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/uploads/{id}/open-for-editing
     * Opens the image file in Adobe Photoshop or Preview on the server machine.
     */
    @PostMapping("/{id}/open-for-editing")
    public ResponseEntity<Map<String, String>> openForEditing(@PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(imageUploadService.openFileForEditing(id));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/uploads/{id}
     * Soft-deletes an upload (moves to Trash). Permanent deletion is via /api/trash.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUpload(@PathVariable("id") Long id) {
        imageUploadService.softDeleteUpload(id);
        return ResponseEntity.ok().build();
    }

    /**
     * GET /api/uploads/{id}/comments
     * Returns comments for an image upload.
     */
    @GetMapping("/{id}/comments")
    public ResponseEntity<List<CommentDto>> getComments(@PathVariable("id") Long id) {
        return ResponseEntity.ok(imageUploadService.getComments(id));
    }

    /**
     * POST /api/uploads/{id}/comments
     * Adds a comment to an image upload.
     */
    @PostMapping("/{id}/comments")
    public ResponseEntity<CommentDto> addComment(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails) {
        String text = body.get("text");
        String author = userDetails != null ? userDetails.getUsername() : "Anonymous";
        return ResponseEntity.ok(imageUploadService.addComment(id, text, author));
    }

    /**
     * POST /api/uploads/retag
     * Re-runs Gemini analysis on all existing uploads to backfill missing tags (e.g. angle).
     */
    @PostMapping("/retag")
    public ResponseEntity<String> retag() {
        int updated = imageUploadService.retagAll();
        return ResponseEntity.ok("Re-tagged " + updated + " uploads");
    }

    /**
     * POST /api/uploads/backfill-previews
     * Regenerates thumbnails for existing PSD/RAW/MP4 uploads that don't have one yet (uploaded
     * before preview generation existed, or whose original generation attempt failed).
     */
    @PostMapping("/backfill-previews")
    public ResponseEntity<String> backfillPreviews() {
        int updated = imageUploadService.backfillPreviews();
        return ResponseEntity.ok("Generated previews for " + updated + " uploads");
    }

    /**
     * PUT /api/uploads/{id}/angle
     * Updates the angle tag for an upload.
     */
    @PutMapping("/{id}/angle")
    public ResponseEntity<String> updateAngle(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        String angle = body.get("angle");
        imageUploadService.updateAngleTag(id, angle);
        return ResponseEntity.ok("Angle updated");
    }

    /**
     * POST /api/uploads/{id}/tags
     * Adds a new tag (category + value) to an upload.
     */
    @PostMapping("/{id}/tags")
    public ResponseEntity<String> addTag(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        imageUploadService.addTag(id, body.get("category"), body.get("value"));
        return ResponseEntity.ok("Tag added");
    }

    /**
     * PUT /api/uploads/{id}/tags
     * Upserts a singleton tag (replaces existing value for the given category).
     */
    @PutMapping("/{id}/tags")
    public ResponseEntity<String> upsertTag(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        imageUploadService.upsertTag(id, body.get("category"), body.get("value"));
        return ResponseEntity.ok("Tag updated");
    }

    /**
     * DELETE /api/uploads/{id}/tags?category=...&value=...
     * Deletes a specific tag by category and value.
     */
    @DeleteMapping("/{id}/tags")
    public ResponseEntity<String> deleteTag(
            @PathVariable("id") Long id,
            @RequestParam("category") String category,
            @RequestParam("value") String value) {
        imageUploadService.deleteTag(id, category, value);
        return ResponseEntity.ok("Tag deleted");
    }

    /**
     * GET /api/uploads/faces
     * Returns all detected face groups with occurrence counts and a representative thumbnail.
     */
    @GetMapping("/faces")
    public ResponseEntity<List<FaceGroupDto>> getFaceGroups() {
        return ResponseEntity.ok(imageUploadService.getFaceGroups());
    }

    /**
     * GET /api/uploads/faces/{faceLabel}
     * Returns all images containing the given face_id.
     */
    @GetMapping("/faces/{faceLabel}")
    public ResponseEntity<List<ImageUploadDto>> getImagesByFace(@PathVariable("faceLabel") String faceLabel) {
        return ResponseEntity.ok(imageUploadService.getImagesByFaceId(faceLabel));
    }

    /**
     * POST /api/uploads/faces/merge
     * Body: { "sourceLabel": "person_5", "targetLabel": "person_2" }
     * Reassigns all images tagged with sourceLabel to targetLabel and removes the source FaceGroup.
     */
    @PostMapping("/faces/merge")
    public ResponseEntity<String> mergeFaces(@RequestBody Map<String, String> body) {
        String source = body.get("sourceLabel");
        String target = body.get("targetLabel");
        try {
            imageUploadService.mergeFaceGroups(source, target);
            return ResponseEntity.ok("Merged " + source + " into " + target);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * POST /api/uploads/qc/batch
     * Triggers async QC validation on all images not yet PASSED.
     */
    @PostMapping("/qc/batch")
    public ResponseEntity<String> runBatchQc() {
        imageQcService.runBatchQc();
        return ResponseEntity.ok("QC batch job started");
    }

    /**
     * POST /api/uploads/{id}/qc
     * Runs QC validation on a single image for all marketplaces.
     */
    @PostMapping("/{id}/qc")
    public ResponseEntity<List<Map<String, String>>> runSingleQc(@PathVariable("id") Long id) {
        List<ImageQcService.MarketplaceQcResult> results = imageQcService.validateSingle(id);
        return ResponseEntity.ok(results.stream()
                .map(r -> Map.of("marketplace", r.marketplace(), "status", r.status(), "details", r.details(), "checkedAt", r.checkedAt()))
                .toList());
    }

    /**
     * POST /api/uploads/batch-rename
     * Renames multiple uploads. Body: [ { "id": 1, "newFileName": "SKU_Front.jpg" }, ... ]
     */
    @PostMapping("/batch-rename")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CREATIVE_LEAD', 'PROJECT_MANAGER', 'CONTENT_MANAGER', 'DESIGNER', 'REVIEWER', 'CLIENT')")
    public ResponseEntity<List<ImageUploadDto>> batchRename(@RequestBody List<Map<String, Object>> renames) {
        List<ImageUploadDto> results = imageUploadService.batchRename(renames);
        return ResponseEntity.ok(results);
    }

    /**
     * PUT /api/uploads/{id}/seo
     * Updates the SEO fields (imageTitle, altText, description) for an upload.
     */
    @PutMapping("/{id}/seo")
    public ResponseEntity<String> updateSeoFields(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        imageUploadService.updateSeoFields(id, body);
        return ResponseEntity.ok("SEO fields updated");
    }

    /**
     * PATCH /api/uploads/{id}/workflow-status
     * Updates the workflow status of an image upload.
     */
    @PatchMapping("/{id}/workflow-status")
    public ResponseEntity<String> updateWorkflowStatus(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body) {
        try {
            String status = body.get("workflowStatus");
            imageUploadService.updateWorkflowStatus(id, status);
            return ResponseEntity.ok("Workflow status updated");
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * PATCH /api/uploads/assign-bulk
     * Assigns a set of uploads to a team member (or clears assignment when memberId is omitted).
     */
    @PatchMapping("/assign-bulk")
    public ResponseEntity<List<ImageUploadDto>> assignUploadsBulk(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("uploadIds");
        Long memberId = body.get("memberId") != null ? Long.valueOf(body.get("memberId").toString()) : null;
        List<Long> uploadIds = rawIds == null ? List.of() : rawIds.stream().map(o -> Long.valueOf(o.toString())).toList();
        return ResponseEntity.ok(imageUploadService.assignUploadsToUser(uploadIds, memberId));
    }

    /**
     * PATCH /api/uploads/move-bulk
     * Moves a set of uploads into a different batch (works for a single asset or many).
     */
    @PatchMapping("/move-bulk")
    public ResponseEntity<List<ImageUploadDto>> moveUploadsBulk(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("uploadIds");
        Long batchId = Long.valueOf(body.get("batchId").toString());
        List<Long> uploadIds = rawIds == null ? List.of() : rawIds.stream().map(o -> Long.valueOf(o.toString())).toList();
        return ResponseEntity.ok(imageUploadService.moveUploadsToBatch(uploadIds, batchId));
    }

    /**
     * GET /api/uploads/{id}/qc
     * Returns stored QC results for an image across all marketplaces.
     */
    @GetMapping("/{id}/qc")
    public ResponseEntity<List<Map<String, String>>> getQcResults(@PathVariable("id") Long id) {
        List<ImageQcService.MarketplaceQcResult> results = imageQcService.getQcResults(id);
        return ResponseEntity.ok(results.stream()
                .map(r -> Map.of("marketplace", r.marketplace(), "status", r.status(), "details", r.details(), "checkedAt", r.checkedAt()))
                .toList());
    }
}
