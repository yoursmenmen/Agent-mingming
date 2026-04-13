package com.mingming.agent.react;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

class ContextWindowManagerTest {

    @Test
    void trimInPlace_shouldPreservePrefixAndKeepNewestTail() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new SystemMessage("summary"));
        messages.add(new UserMessage("u1"));
        messages.add(new AssistantMessage("a1"));
        messages.add(new UserMessage("u2"));
        messages.add(new AssistantMessage("a2"));

        ContextWindowManager manager = new ContextWindowManager(new ContextWindowPolicy(4, 100));
        manager.trimInPlace(messages, 2);

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getText()).isEqualTo("system");
        assertThat(messages.get(1).getText()).isEqualTo("summary");
        assertThat(messages.get(2).getText()).isEqualTo("u2");
        assertThat(messages.get(3).getText()).isEqualTo("a2");
    }

    @Test
    void trimInPlace_shouldRespectCharacterBudget() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new UserMessage("1234567890"));
        messages.add(new AssistantMessage("abc"));
        messages.add(new UserMessage("xyz"));

        ContextWindowManager manager = new ContextWindowManager(new ContextWindowPolicy(10, 6));
        manager.trimInPlace(messages, 1);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getText()).isEqualTo("system");
        assertThat(messages.get(1).getText()).isEqualTo("abc");
        assertThat(messages.get(2).getText()).isEqualTo("xyz");
    }
}
