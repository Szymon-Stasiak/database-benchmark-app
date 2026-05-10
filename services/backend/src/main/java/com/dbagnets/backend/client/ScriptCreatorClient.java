package com.dbagnets.backend.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

@Service
public class ScriptCreatorClient {
    private static final Logger log = LoggerFactory.getLogger(ScriptCreatorClient.class);
    private final WebClient webClient;

    public ScriptCreatorClient(@Value("${script-creator.base-url}") String baseUrl) {
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .build();
    }

    public ScriptCreatorResponse generate(String idea, int depth, List<ScriptCreatorRequest.TargetRequest> targets) {
        var request = new ScriptCreatorRequest(
            idea, depth, targets,
            "vertex_ai/claude-sonnet-4-6",
            10, false
        );

        log.info("Calling script-creator with {} targets for idea: {}", targets.size(), idea);
        return webClient.post()
            .uri("/generate")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(ScriptCreatorResponse.class)
            .timeout(Duration.ofMinutes(10))
            .block();
    }
}
