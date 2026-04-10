package com.mingming.agent.react.tool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ToolConfirmRegistry {

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    /**
     * 挂起当前线程等待用户确认，超时返回 false（视为跳过）。
     */
    public boolean awaitConfirm(String toolCallId, long timeoutMs) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(toolCallId, future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        } finally {
            pending.remove(toolCallId);
        }
    }

    /**
     * 由 ToolConfirmController 调用，解除挂起。
     */
    public void resolve(String toolCallId, boolean approved) {
        CompletableFuture<Boolean> future = pending.get(toolCallId);
        if (future != null) {
            future.complete(approved);
        }
    }

    public boolean hasPending(String toolCallId) {
        return pending.containsKey(toolCallId);
    }
}
