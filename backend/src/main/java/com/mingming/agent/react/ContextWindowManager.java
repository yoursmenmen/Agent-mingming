package com.mingming.agent.react;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.ai.chat.messages.Message;

public class ContextWindowManager {

    private final ContextWindowPolicy policy;

    public ContextWindowManager(ContextWindowPolicy policy) {
        this.policy = policy;
    }

    public void trimInPlace(List<Message> messages, int fixedPrefixCount) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int prefixCount = Math.max(0, Math.min(fixedPrefixCount, messages.size()));
        List<Message> prefix = new ArrayList<>(messages.subList(0, prefixCount));
        List<Message> tailSource = messages.subList(prefixCount, messages.size());

        int tailMessageBudget = Math.max(0, policy.maxMessages() - prefixCount);
        List<Message> reversedSelected = new ArrayList<>();
        int totalChars = 0;

        for (int i = tailSource.size() - 1; i >= 0; i--) {
            if (reversedSelected.size() >= tailMessageBudget) {
                break;
            }
            Message candidate = tailSource.get(i);
            int messageLength = messageLength(candidate);

            if (!reversedSelected.isEmpty() && totalChars + messageLength > policy.maxChars()) {
                break;
            }
            if (reversedSelected.isEmpty() && messageLength > policy.maxChars()) {
                continue;
            }
            reversedSelected.add(candidate);
            totalChars += messageLength;
        }

        Collections.reverse(reversedSelected);
        messages.clear();
        messages.addAll(prefix);
        messages.addAll(reversedSelected);
    }

    private int messageLength(Message message) {
        if (message == null || message.getText() == null) {
            return 0;
        }
        return message.getText().length();
    }
}
