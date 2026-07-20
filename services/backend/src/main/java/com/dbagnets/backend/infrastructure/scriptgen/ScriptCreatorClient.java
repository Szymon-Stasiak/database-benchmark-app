package com.dbagnets.backend.infrastructure.scriptgen;

import com.dbagnets.backend.benchmark.setup.port.ScriptGenerationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
public class ScriptCreatorClient implements ScriptGenerationPort {
    private final WebClient webClient;
    private final String model;

    public ScriptCreatorClient(
            @Value("${script-creator.base-url}") String baseUrl,
            @Value("${script-creator.model}") String model) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        this.model = model;
    }

    public ScriptCreatorResponse generate(String idea, int depth, List<ScriptCreatorRequest.TargetRequest> targets) {
        var request = new ScriptCreatorRequest(
                idea, depth, targets,
                model,
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