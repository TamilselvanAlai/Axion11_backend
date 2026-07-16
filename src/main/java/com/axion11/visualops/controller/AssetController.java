package com.axion11.visualops.controller;

import com.axion11.visualops.controller.dto.AddCommentRequest;
import com.axion11.visualops.controller.dto.AssetDetailDto;
import com.axion11.visualops.service.AssetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173", maxAge = 3600)
public class AssetController {

    private final AssetService assetService;

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getAssetDetails(@PathVariable("id") String id) {
        System.out.println("DEBUG: getAssetDetails called with id=" + id);
        try {
            return ResponseEntity.ok(assetService.getAssetDetails(id));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CREATIVE_LEAD', 'PROJECT_MANAGER', 'CONTENT_MANAGER', 'DESIGNER', 'REVIEWER', 'CLIENT')")
    public ResponseEntity<?> addComment(
            @PathVariable("id") String id,
            @Valid @RequestBody AddCommentRequest request,
            Authentication authentication) {
        try {
            String authorName = authentication != null ? authentication.getName() : "Anonymous Reviewer";
            return ResponseEntity.ok(assetService.addComment(id, request, authorName));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PutMapping("/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> editComment(
            @PathVariable("commentId") Long commentId,
            @RequestBody java.util.Map<String, String> body) {
        try {
            return ResponseEntity.ok(assetService.editComment(commentId, body.get("text")));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @DeleteMapping("/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> deleteComment(@PathVariable("commentId") Long commentId) {
        try {
            assetService.deleteComment(commentId);
            return ResponseEntity.ok("Comment deleted");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CREATIVE_LEAD', 'REVIEWER', 'CLIENT')")
    public ResponseEntity<AssetDetailDto> approveAsset(@PathVariable("id") String id) {
        return ResponseEntity.ok(assetService.approveAsset(id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CREATIVE_LEAD', 'REVIEWER', 'CLIENT')")
    public ResponseEntity<AssetDetailDto> rejectAsset(@PathVariable("id") String id) {
        return ResponseEntity.ok(assetService.rejectAsset(id));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'CREATIVE_LEAD', 'REVIEWER', 'CLIENT')")
    public ResponseEntity<?> publishAsset(@PathVariable("id") String id) {
        try {
            return ResponseEntity.ok(assetService.publishAsset(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
