package com.axion11.visualops.controller;

import com.axion11.visualops.models.dto.ProjectTreeNode;
import com.axion11.visualops.service.ProjectTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/tree")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ProjectTreeController {

    private final ProjectTreeService projectTreeService;

    @GetMapping
    public ResponseEntity<List<ProjectTreeNode>> getProjectTree() {
        return ResponseEntity.ok(projectTreeService.getProjectTree());
    }
}
