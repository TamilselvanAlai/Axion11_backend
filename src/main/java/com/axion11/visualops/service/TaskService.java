package com.axion11.visualops.service;

import com.axion11.visualops.controller.dto.*;
import com.axion11.visualops.models.*;
import com.axion11.visualops.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final SubtaskRepository subtaskRepository;
    private final ProjectRepository projectRepository;
    private final BatchRepository batchRepository;
    private final UserRepository userRepository;

    // ── Status enum mappings ───────────────────────────────────────────────

    private TaskStatus parseStatus(String s) {
        if (s == null) return TaskStatus.TO_DO;
        return switch (s.toLowerCase()) {
            case "to-do", "not-started" -> TaskStatus.TO_DO;
            case "ready" -> TaskStatus.READY;
            case "in-progress" -> TaskStatus.IN_PROGRESS;
            case "review" -> TaskStatus.REVIEW;
            case "blocked" -> TaskStatus.BLOCKED;
            case "completed", "done" -> TaskStatus.COMPLETED;
            default -> TaskStatus.TO_DO;
        };
    }

    /** Sets task.status and keeps completedAt in sync: stamped on entry into COMPLETED,
     *  cleared when moved back out. Centralized here so createTask/updateTask/moveTask agree. */
    private void applyStatus(Task task, TaskStatus newStatus) {
        boolean enteringCompleted = newStatus == TaskStatus.COMPLETED && task.getStatus() != TaskStatus.COMPLETED;
        boolean leavingCompleted = newStatus != TaskStatus.COMPLETED && task.getStatus() == TaskStatus.COMPLETED;
        task.setStatus(newStatus);
        if (enteringCompleted) {
            task.setCompletedAt(LocalDateTime.now());
        } else if (leavingCompleted) {
            task.setCompletedAt(null);
        }
    }

    private String formatStatus(TaskStatus s) {
        return switch (s) {
            case TO_DO -> "to-do";
            case READY -> "ready";
            case IN_PROGRESS -> "in-progress";
            case REVIEW -> "review";
            case BLOCKED -> "blocked";
            case COMPLETED -> "completed";
        };
    }

    private TaskType parseTaskType(String s) {
        if (s == null) return TaskType.PRODUCTION;
        return switch (s.toLowerCase().replace("-", "_")) {
            case "pre_production" -> TaskType.PRE_PRODUCTION;
            case "post_production" -> TaskType.POST_PRODUCTION;
            default -> TaskType.PRODUCTION;
        };
    }

    private String formatTaskType(TaskType t) {
        if (t == null) return "production";
        return switch (t) {
            case PRE_PRODUCTION -> "pre-production";
            case POST_PRODUCTION -> "post-production";
            default -> "production";
        };
    }

    private Priority parsePriority(String s) {
        if (s == null) return Priority.MEDIUM;
        return switch (s.toLowerCase()) {
            case "low" -> Priority.LOW;
            case "high" -> Priority.HIGH;
            default -> Priority.MEDIUM;
        };
    }

    private String formatPriority(Priority p) {
        if (p == null) return "medium";
        return p.name().toLowerCase();
    }

    // ── DTO mapping ────────────────────────────────────────────────────────

    private SubtaskDto toSubtaskDto(Subtask st) {
        return SubtaskDto.builder()
                .id(st.getId())
                .title(st.getTitle())
                .completed(st.isCompleted())
                .owner(st.getOwner() != null ? st.getOwner().getName() : null)
                .dueDate(st.getDueDate())
                .build();
    }

    public TaskDto toDto(Task task) {
        List<String> deps = new ArrayList<>();
        if (task.getDependencies() != null && !task.getDependencies().isBlank()) {
            deps = Arrays.asList(task.getDependencies().split(","));
        }

        List<SubtaskDto> subtaskDtos = task.getSubtasks() == null ? List.of() :
                task.getSubtasks().stream().map(this::toSubtaskDto).collect(Collectors.toList());

        return TaskDto.builder()
                .id(task.getId())
                .name(task.getTitle())
                .status(formatStatus(task.getStatus()))
                .owner(task.getOwner() != null ? task.getOwner().getName() : null)
                .ownerEmail(task.getOwner() != null ? task.getOwner().getEmail() : null)
                .projectId(task.getProject().getId())
                .projectName(task.getProject().getName())
                .batchId(task.getBatch() != null ? task.getBatch().getId() : null)
                .batchName(task.getBatch() != null ? task.getBatch().getName() : null)
                .startDate(task.getStartDate())
                .dueDate(task.getDueDate())
                .dependencies(deps)
                .linkedAssets(task.getLinkedAssets())
                .description(task.getDescription())
                .taskType(formatTaskType(task.getTaskType()))
                .priority(formatPriority(task.getPriority()))
                .subtasks(subtaskDtos)
                .completedAt(task.getCompletedAt())
                .build();
    }

    // ── Queries ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TaskGroupDto> getAllTaskGroups() {
        List<Project> projects = projectRepository.findAll();
        return projects.stream().map(project -> {
            List<Task> tasks = taskRepository.findByProjectId(project.getId());
            return TaskGroupDto.builder()
                    .id(project.getId())
                    .name(project.getName())
                    .tasks(tasks.stream().map(this::toDto).collect(Collectors.toList()))
                    .build();
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TaskDto getTask(Long id) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Task not found: " + id));
        return toDto(task);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Transactional
    public TaskDto createTask(CreateTaskRequest req) {
        Project project = projectRepository.findById(req.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found: " + req.getProjectId()));

        Batch batch = null;
        if (req.getBatchId() != null) {
            batch = batchRepository.findById(req.getBatchId())
                    .orElseThrow(() -> new RuntimeException("Batch not found: " + req.getBatchId()));
            if (batch.getProject() == null || !batch.getProject().getId().equals(project.getId())) {
                throw new IllegalArgumentException("Batch " + req.getBatchId() + " does not belong to project " + req.getProjectId());
            }
        }

        User owner = null;
        if (req.getOwnerEmail() != null && !req.getOwnerEmail().isBlank()) {
            owner = userRepository.findByEmail(req.getOwnerEmail()).orElse(null);
        }

        String deps = req.getDependencies() == null ? null :
                req.getDependencies().stream().map(String::valueOf).collect(Collectors.joining(","));

        Task task = Task.builder()
                .title(req.getTitle())
                .status(parseStatus(req.getStatus()))
                .owner(owner)
                .project(project)
                .batch(batch)
                .startDate(req.getStartDate())
                .dueDate(req.getDueDate())
                .dependencies(deps)
                .linkedAssets(req.getLinkedAssets())
                .description(req.getDescription())
                .taskType(parseTaskType(req.getTaskType()))
                .priority(parsePriority(req.getPriority()))
                .build();

        if (task.getStatus() == TaskStatus.COMPLETED) {
            task.setCompletedAt(LocalDateTime.now());
        }

        task = taskRepository.save(task);

        if (req.getSubtasks() != null) {
            for (CreateTaskRequest.SubtaskRequest sr : req.getSubtasks()) {
                User stOwner = sr.getOwnerEmail() != null ?
                        userRepository.findByEmail(sr.getOwnerEmail()).orElse(null) : null;
                Subtask st = Subtask.builder()
                        .title(sr.getTitle())
                        .completed(sr.isCompleted())
                        .owner(stOwner)
                        .dueDate(sr.getDueDate())
                        .task(task)
                        .build();
                subtaskRepository.save(st);
                task.getSubtasks().add(st);
            }
        }

        return toDto(task);
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Transactional
    public TaskDto updateTask(Long id, UpdateTaskRequest req) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Task not found: " + id));

        if (req.getTitle() != null) task.setTitle(req.getTitle());
        if (req.getStatus() != null) applyStatus(task, parseStatus(req.getStatus()));
        if (req.getStartDate() != null) task.setStartDate(req.getStartDate());
        if (req.getDueDate() != null) task.setDueDate(req.getDueDate());
        if (req.getLinkedAssets() != null) task.setLinkedAssets(req.getLinkedAssets());
        if (req.getDescription() != null) task.setDescription(req.getDescription());
        if (req.getTaskType() != null) task.setTaskType(parseTaskType(req.getTaskType()));
        if (req.getPriority() != null) task.setPriority(parsePriority(req.getPriority()));

        if (req.getOwnerEmail() != null) {
            User owner = userRepository.findByEmail(req.getOwnerEmail()).orElse(null);
            task.setOwner(owner);
        }

        if (req.getProjectId() != null) {
            Project project = projectRepository.findById(req.getProjectId())
                    .orElseThrow(() -> new RuntimeException("Project not found"));
            task.setProject(project);
            // Re-parenting to a different project invalidates the old batch — clear it unless
            // the client explicitly sends a batchId below.
            if (task.getBatch() != null && (task.getBatch().getProject() == null
                    || !task.getBatch().getProject().getId().equals(project.getId()))) {
                task.setBatch(null);
            }
        }

        if (req.getBatchId() != null) {
            // 0 is the "clear batch" sentinel — partial-update semantics already use null for "no change".
            if (req.getBatchId() == 0L) {
                task.setBatch(null);
            } else {
                Batch batch = batchRepository.findById(req.getBatchId())
                        .orElseThrow(() -> new RuntimeException("Batch not found: " + req.getBatchId()));
                if (batch.getProject() == null
                        || !batch.getProject().getId().equals(task.getProject().getId())) {
                    throw new IllegalArgumentException(
                            "Batch " + req.getBatchId() + " does not belong to project " + task.getProject().getId());
                }
                task.setBatch(batch);
            }
        }

        if (req.getDependencies() != null) {
            task.setDependencies(req.getDependencies().stream()
                    .map(String::valueOf).collect(Collectors.joining(",")));
        }

        return toDto(taskRepository.save(task));
    }

    // ── Move (status only) ─────────────────────────────────────────────────

    @Transactional
    public TaskDto moveTask(Long id, String status) {
        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Task not found: " + id));
        applyStatus(task, parseStatus(status));
        return toDto(taskRepository.save(task));
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Transactional
    public void deleteTask(Long id) {
        if (!taskRepository.existsById(id)) {
            throw new RuntimeException("Task not found: " + id);
        }
        taskRepository.deleteById(id);
    }
}
