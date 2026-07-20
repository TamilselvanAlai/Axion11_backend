package com.axion11.visualops.service;

import com.axion11.visualops.controller.dto.AddCommentRequest;
import com.axion11.visualops.controller.dto.AssetDetailDto;
import com.axion11.visualops.controller.dto.CommentDto;
import com.axion11.visualops.models.Comment;
import com.axion11.visualops.models.ImageUpload;
import com.axion11.visualops.repository.CommentRepository;
import com.axion11.visualops.repository.ImageUploadRepository;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final ImageUploadRepository imageUploadRepository;
    private final CommentRepository commentRepository;
    private final CommentClassifierService commentClassifier;

    @Value("${gcs.bucket.name}")
    private String bucketName;

    @Transactional(readOnly = true)
    public AssetDetailDto getAssetDetails(String idOrExternalId) {
        ImageUpload upload = findUpload(idOrExternalId);
        return mapToDto(upload);
    }

    @Transactional
    public AssetDetailDto addComment(String idOrExternalId, AddCommentRequest request, String authorName) {
        ImageUpload upload = findUpload(idOrExternalId);
        var classification = commentClassifier.classify(request.getText());

        Comment comment = Comment.builder()
                .text(request.getText())
                .authorName(authorName)
                .imageUpload(upload)
                .markX(request.getMarkX())
                .markY(request.getMarkY())
                .feedbackCategory(classification != null ? classification.category() : null)
                .feedbackSubcategory(classification != null ? classification.subcategory() : null)
                .feedbackSeverity(classification != null ? classification.severity() : null)
                .build();
        comment = commentRepository.save(comment);

        // Upload annotation image to GCS if provided. This stays attached to the comment on the
        // current version — it does not create a new image version. Clicking the comment shows
        // the marked spot on the asset's current image instead of swapping to a separate snapshot.
        if (request.getAnnotationImage() != null && !request.getAnnotationImage().isEmpty()) {
            try {
                String rawData = request.getAnnotationImage();
                // Detect content type from data URL prefix
                String contentType = "image/jpeg";
                String ext = "jpg";
                if (rawData.startsWith("data:image/png")) {
                    contentType = "image/png";
                    ext = "png";
                }
                // Strip data:image/...;base64, prefix
                String base64 = rawData.contains(",") ? rawData.substring(rawData.indexOf(",") + 1) : rawData;
                byte[] imageBytes = Base64.getDecoder().decode(base64);

                String objectName = "comment_annotations/" + upload.getId() + "-comment-" + comment.getId() + "." + ext;
                Storage storage = StorageOptions.getDefaultInstance().getService();
                BlobId blobId = BlobId.of(bucketName, objectName);
                BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                        .setContentType(contentType)
                        .build();
                storage.create(blobInfo, imageBytes);

                String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + objectName;
                comment.setAnnotationImageUrl(publicUrl);
                commentRepository.save(comment);
            } catch (Exception e) {
                log.error("Failed to upload annotation image for comment {}: {}", comment.getId(), e.getMessage());
            }
        }

        upload.getComments().add(comment);
        return mapToDto(upload);
    }

    @Transactional
    public CommentDto editComment(Long commentId, String newText) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new RuntimeException("Comment not found: " + commentId));
        comment.setText(newText);
        commentRepository.save(comment);
        CommentDto dto = new CommentDto();
        dto.setId(comment.getId());
        dto.setText(comment.getText());
        dto.setAuthorName(comment.getAuthorName());
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setResolved(comment.isResolved());
        dto.setAnnotationImageUrl(comment.getAnnotationImageUrl());
        return dto;
    }

    @Transactional
    public void deleteComment(Long commentId) {
        commentRepository.deleteById(commentId);
    }

    /** Flips the asset's status to "approved" in place, and advances its version number to
     *  claim the next free slot in its chain — no new row, but an approved version is visibly
     *  distinguishable from the raw draft it came from (e.g. draft v1 -> approved v2). Later
     *  edits/approvals keep advancing from wherever the chain's numbering currently sits, so
     *  the sequence stays gap-free and monotonically increasing either way. */
    @Transactional
    public AssetDetailDto approveAsset(String idOrExternalId) {
        ImageUpload upload = findUpload(idOrExternalId);
        upload.setApprovalStatus("approved");
        upload.setVersionNumber(nextChainVersionNumber(upload));
        imageUploadRepository.save(upload);
        return mapToDto(upload);
    }

    private int nextChainVersionNumber(ImageUpload upload) {
        Long rootId = upload.getOriginalUploadId() != null ? upload.getOriginalUploadId() : upload.getId();
        int maxVersion = imageUploadRepository.findById(rootId)
                .map(v1 -> v1.getVersionNumber() != null ? v1.getVersionNumber() : 1)
                .orElse(1);
        for (ImageUpload sibling : imageUploadRepository.findByOriginalUploadIdOrderByVersionNumberAsc(rootId)) {
            int v = sibling.getVersionNumber() != null ? sibling.getVersionNumber() : 1;
            if (v > maxVersion) maxVersion = v;
        }
        return maxVersion + 1;
    }

    @Transactional
    public AssetDetailDto rejectAsset(String idOrExternalId) {
        ImageUpload upload = findUpload(idOrExternalId);
        upload.setApprovalStatus("rejected");
        imageUploadRepository.save(upload);
        return mapToDto(upload);
    }

    /** Publishes an already-approved version live. Only valid from "approved". */
    @Transactional
    public AssetDetailDto publishAsset(String idOrExternalId) {
        ImageUpload upload = findUpload(idOrExternalId);
        if (!"approved".equals(upload.getApprovalStatus())) {
            throw new IllegalStateException("Only approved assets can be published live: " + idOrExternalId);
        }
        upload.setApprovalStatus("live");
        imageUploadRepository.save(upload);
        return mapToDto(upload);
    }

    private ImageUpload findUpload(String idOrExternalId) {
        try {
            Long id = Long.parseLong(idOrExternalId);
            return imageUploadRepository.findById(id)
                    .orElseGet(() -> imageUploadRepository.findByExternalId(idOrExternalId)
                            .orElseThrow(() -> new RuntimeException("Asset not found: " + idOrExternalId)));
        } catch (NumberFormatException e) {
            return imageUploadRepository.findByExternalId(idOrExternalId)
                    .orElseThrow(() -> new RuntimeException("Asset not found: " + idOrExternalId));
        }
    }

    private AssetDetailDto mapToDto(ImageUpload upload) {
        AssetDetailDto dto = new AssetDetailDto();
        dto.setId(upload.getId());
        dto.setExternalId(upload.getExternalId());
        dto.setThumbnail(upload.getPublicUrl());
        dto.setName(upload.getFileName());
        dto.setStatus(upload.getApprovalStatus());
        dto.setAiScore(upload.getAiScore());
        dto.setDimensions(upload.getWidth() != null && upload.getHeight() != null
                ? upload.getWidth() + "×" + upload.getHeight() : null);
        dto.setFileSize(upload.getFileSize() != null ? formatFileSize(upload.getFileSize()) : null);
        dto.setUploadedBy(upload.getUploadedBy() != null ? upload.getUploadedBy().getName() : null);
        dto.setUploadDate(upload.getCreatedAt() != null ? upload.getCreatedAt().toLocalDate().toString() : null);
        dto.setAssignedToName(upload.getAssignedToName());
        dto.setCommentsCount(upload.getComments() != null ? upload.getComments().size() : 0);
        dto.setComments(upload.getComments() != null
                ? upload.getComments().stream().map(this::mapToCommentDto).collect(Collectors.toList())
                : List.of());
        return dto;
    }

    private CommentDto mapToCommentDto(Comment comment) {
        CommentDto dto = new CommentDto();
        dto.setId(comment.getId());
        dto.setText(comment.getText());
        dto.setAuthorName(comment.getAuthorName());
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setResolved(comment.isResolved());
        dto.setAnnotationImageUrl(comment.getAnnotationImageUrl());
        dto.setMarkX(comment.getMarkX());
        dto.setMarkY(comment.getMarkY());
        return dto;
    }

    private String formatFileSize(Long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
