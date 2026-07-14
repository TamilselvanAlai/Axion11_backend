package com.axion11.visualops.controller;

import com.axion11.visualops.controller.dto.*;
import com.axion11.visualops.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /** Returns all projects as task groups, each containing their tasks */
    @GetMapping("/groups")
    public ResponseEntity<List<TaskGroupDto>> getTaskGroups() {
        return ResponseEntity.ok(taskService.getAllTaskGroups());
    }

    /** Get a single task by ID */
    @GetMapping("/{id}")
    public ResponseEntity<TaskDto> getTask(@PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(taskService.getTask(id));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Create a new task */
    @PostMapping
    public ResponseEntity<TaskDto> createTask(@RequestBody CreateTaskRequest request) {
        try {
            TaskDto created = taskService.createTask(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** Update task fields */
    @PutMapping("/{id}")
    public ResponseEntity<TaskDto> updateTask(@PathVariable("id") Long id,
                                              @RequestBody UpdateTaskRequest request) {
        try {
            return ResponseEntity.ok(taskService.updateTask(id, request));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Move task to a new status (for Kanban drag-and-drop) */
    @PatchMapping("/{id}/status")
    public ResponseEntity<TaskDto> moveTask(@PathVariable("id") Long id,
                                            @RequestBody MoveTaskRequest request) {
        try {
            return ResponseEntity.ok(taskService.moveTask(id, request.getStatus()));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Delete a task */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable("id") Long id) {
        try {
            taskService.deleteTask(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
