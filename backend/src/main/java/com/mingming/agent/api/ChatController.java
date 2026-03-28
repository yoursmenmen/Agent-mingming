package com.mingming.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.core.AgentOrchestrator;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatController {

    private final AgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public ChatController(AgentOrchestrator orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    public record ChatRequest(String message) {}

    @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(60_000L);

        // MVP: create a new session+run per request
        var init = orchestrator.startRun("dashscope", null, null, "system.txt");
        UUID runId = init.runId();

        new Thread(() -> {
            try {
                ObjectNode start = objectMapper.createObjectNode();
                start.put("runId", runId.toString());
                emitter.send(SseEmitter.event().name("run").data(start.toString()));

                orchestrator.runOnce(runId, req.message(), (data) -> {
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
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }
}
