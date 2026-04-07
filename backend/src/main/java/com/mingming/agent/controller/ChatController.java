package com.mingming.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.orchestrator.AgentOrchestrator;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class ChatController {

    private final AgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public record ChatRequest(String message, String sessionId) {}

    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(60_000L);

        UUID sessionId = null;
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            try {
                sessionId = UUID.fromString(req.sessionId());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sessionId format");
            }
        }

        // Create a new run; session can be reused when sessionId is provided.
        AgentOrchestrator.RunInit init;
        try {
            init = orchestrator.startRun(sessionId, "dashscope", null, null, "system.txt");
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        UUID runId = init.runId();

        new Thread(() -> {
            try {
                ObjectNode start = objectMapper.createObjectNode();
                start.put("sessionId", init.sessionId().toString());
                start.put("runId", runId.toString());
                emitter.send(SseEmitter.event().name("run").data(start.toString()));

                orchestrator.executeSingleTurn(runId, init.sessionId(), req.message(), data -> {
                    try {
                        emitter.send(SseEmitter.event().name("event").data(data));
                    } catch (IOException e) {
                        // client disconnected
                    }
                });

                emitter.complete();
            } catch (Exception e) {
                try {
                    ObjectNode err = objectMapper.createObjectNode();
                    err.put("message", e.getMessage());
                    emitter.send(SseEmitter.event().name("error").data(err.toString()));
                } catch (IOException ignored) {
                }
                emitter.complete();
            }
        }).start();

        return emitter;
    }
}
