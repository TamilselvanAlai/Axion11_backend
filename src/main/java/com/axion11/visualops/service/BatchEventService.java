package com.axion11.visualops.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes batch upload-status changes to connected clients over SSE instead of clients polling
 * GET /batches/{id} on a timer. Multiple tabs/devices can watch the same batch at once, so each
 * batch id fans out to a list of emitters rather than a single one.
 */
@Slf4j
@Service
public class BatchEventService {

    private final Map<Long, List<SseEmitter>> emittersByBatch = new ConcurrentHashMap<>();

    /** Matches the desktop/web poll timeout this replaces — long enough to cover slow batches,
     *  short enough that a forgotten connection doesn't linger forever. */
    private static final long EMITTER_TIMEOUT_MS = 10 * 60 * 1000L;

    public SseEmitter subscribe(Long batchId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        List<SseEmitter> emitters = emittersByBatch.computeIfAbsent(batchId, id -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        Runnable cleanup = () -> {
            emitters.remove(emitter);
            emittersByBatch.computeIfPresent(batchId, (id, list) -> list.isEmpty() ? null : list);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // An initial event lets the client tell "connected, no update yet" apart from "still
        // connecting" — without it a batch that's already done by the time the client subscribes
        // would sit silent until the next real change (which may never come).
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("batchId", batchId)));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /** Called from whichever thread flips a batch's uploadStatus (background upload executors,
     *  request threads for the zero-file case) — safe to call with no subscribers listening. */
    public void publishStatus(Long batchId, String status) {
        List<SseEmitter> emitters = emittersByBatch.get(batchId);
        if (emitters == null || emitters.isEmpty()) return;

        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name("status").data(Map.of("batchId", batchId, "uploadStatus", status)));
                if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping stale SSE emitter for batch {}: {}", batchId, e.getMessage());
                emitter.completeWithError(e);
            }
        }
    }
}
