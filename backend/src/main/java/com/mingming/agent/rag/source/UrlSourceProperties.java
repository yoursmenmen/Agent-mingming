package com.mingming.agent.rag.source;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "agent.rag.sources.url")
public class UrlSourceProperties {

    private boolean enabled = false;

    private int maxInMemorySizeBytes = 4 * 1024 * 1024;

    private List<Item> items = new ArrayList<>();

    @Getter
    @Setter
    public static class Item {
        private String name;
        private String url;
        private boolean enabled = true;
    }
}
