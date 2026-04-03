package com.mingming.agent.event.contract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventContractRegistry {

    private static final Logger log = LoggerFactory.getLogger(EventContractRegistry.class);

    private final List<RunEventContract> contracts;
    private final Map<RunEventType, RunEventContract> contractMap = new EnumMap<>(RunEventType.class);

    private RunEventContract contractFor(RunEventType type) {
        if (contractMap.isEmpty()) {
            for (RunEventContract contract : contracts) {
                contractMap.put(contract.eventType(), contract);
            }
        }
        return contractMap.get(type);
    }

    public ObjectNode normalizeAndValidate(RunEventType type, ObjectNode payload) {
        if (type == null || payload == null) {
            return payload;
        }
        RunEventContract contract = contractFor(type);
        if (contract == null) {
            return payload;
        }

        ObjectNode normalized = contract.normalize(payload);
        List<String> errors = contract.validate(normalized);
        if (!errors.isEmpty()) {
            log.warn("Run event payload contract warnings: type={}, errors={}", type, errors);
            normalized.put("contractWarnings", String.join("; ", errors));
        }
        return normalized;
    }
}
