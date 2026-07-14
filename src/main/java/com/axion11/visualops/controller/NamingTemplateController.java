package com.axion11.visualops.controller;

import com.axion11.visualops.controller.dto.NamingTemplateDto;
import com.axion11.visualops.service.NamingTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/naming-templates")
@RequiredArgsConstructor
public class NamingTemplateController {

    private final NamingTemplateService service;

    @GetMapping
    public ResponseEntity<List<NamingTemplateDto>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<NamingTemplateDto> getById(@PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(service.getById(id));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<NamingTemplateDto> create(@RequestBody NamingTemplateDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<NamingTemplateDto> update(@PathVariable("id") Long id, @RequestBody NamingTemplateDto request) {
        try {
            return ResponseEntity.ok(service.update(id, request));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
