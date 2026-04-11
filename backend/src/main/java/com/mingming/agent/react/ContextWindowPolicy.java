package com.mingming.agent.react;

public record ContextWindowPolicy(int maxMessages, int maxChars) {

    public ContextWindowPolicy {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be positive");
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
    }
}
