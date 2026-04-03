package com.mingming.agent.event.contract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import java.util.List;

public interface RunEventContract {

    RunEventType eventType();

    ObjectNode normalize(ObjectNode payload);

    List<String> validate(ObjectNode payload);
}
