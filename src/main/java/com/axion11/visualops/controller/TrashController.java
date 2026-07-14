package com.axion11.visualops.controller;

import com.axion11.visualops.service.TrashService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trash")
@RequiredArgsConstructor
public class TrashController {

    private final TrashService trashService;

    /** GET /api/trash — list all soft-deleted items (uploads + batches). */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getTrashItems() {
        return ResponseEntity.ok(trashService.getTrashItems());
    }

    /** POST /api/trash/uploads/{id}/restore — restore a soft-deleted upload. */
    @PostMapping("/uploads/{id}/restore")
    public ResponseEntity<Void> restoreUpload(@PathVariable("id") Long id) {
        trashService.restoreUpload(id);
        return ResponseEntity.ok().build();
    }

    /** POST /api/trash/batches/{id}/restore — restore a soft-deleted batch. */
    @PostMapping("/batches/{id}/restore")
    public ResponseEntity<Void> restoreBatch(@PathVariable("id") Long id) {
        trashService.restoreBatch(id);
        return ResponseEntity.ok().build();
    }

    /** DELETE /api/trash/uploads/{id} — permanently delete a trashed upload. */
    @DeleteMapping("/uploads/{id}")
    public ResponseEntity<Void> permanentDeleteUpload(@PathVariable("id") Long id) {
        trashService.permanentDeleteUpload(id);
        return ResponseEntity.ok().build();
    }

    /** DELETE /api/trash/batches/{id} — permanently delete a trashed batch. */
    @DeleteMapping("/batches/{id}")
    public ResponseEntity<Void> permanentDeleteBatch(@PathVariable("id") Long id) {
        trashService.permanentDeleteBatch(id);
        return ResponseEntity.ok().build();
    }

    /** DELETE /api/trash/clear — purge items older than 30 days. */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Integer>> clearOld() {
        int count = trashService.clearOldItems();
        return ResponseEntity.ok(Map.of("cleared", count));
    }

    /** DELETE /api/trash/clear-all — permanently delete everything in trash. */
    @DeleteMapping("/clear-all")
    public ResponseEntity<Map<String, Integer>> clearAll() {
        int count = trashService.clearAll();
        return ResponseEntity.ok(Map.of("cleared", count));
    }
}
