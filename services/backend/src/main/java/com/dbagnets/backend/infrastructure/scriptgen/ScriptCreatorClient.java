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

    private static final int MAX_RESPONSE_BODY_MB = 10;
    private static final int BYTES_PER_MB = 1024 * 1024;

    private final WebClient webClient;
    private final String model;
    private final String generatePath;
    private final int maxIterations;
    private final int timeoutMinutes;

    public ScriptCreatorClient(
            @Value("${script-creator.base-url}") String baseUrl,
            @Value("${script-creator.model}") String model,
            @Value("${script-creator.generate-path}") String generatePath,
            @Value("${script-creator.max-iterations}") int maxIterations,
            @Value("${script-creator.timeout-minutes}") int timeoutMinutes) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(MAX_RESPONSE_BODY_MB * BYTES_PER_MB))
                .build();
        this.model = model;
        this.generatePath = generatePath;
        this.maxIterations = maxIterations;
        this.timeoutMinutes = timeoutMinutes;
    }

    public ScriptCreatorResponse generate(String idea, int depth, List<ScriptCreatorRequest.TargetRequest> targets) {
        var request = new ScriptCreatorRequest(idea, depth, targets, model, maxIterations, false);

        log.info("Calling script-creator with {} targets for idea: {}", targets.size(), idea);
        return webClient.post()
                .uri(generatePath)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ScriptCreatorResponse.class)
                .timeout(Duration.ofMinutes(timeoutMinutes))
                .block();
    }
}