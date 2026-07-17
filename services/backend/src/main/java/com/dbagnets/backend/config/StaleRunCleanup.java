package com.dbagnets.backend.config;

import com.dbagnets.backend.benchmark.execution.BenchmarkResult;
import com.dbagnets.backend.benchmark.execution.BenchmarkRun;
import com.dbagnets.backend.benchmark.execution.BenchmarkRunRepository;
import com.dbagnets.backend.benchmark.execution.RunStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class StaleRunCleanup implements CommandLineRunner {

    private static final String STALE_MESSAGE = "Backend restarted while run was active";

    private final BenchmarkRunRepository runRepository;

    @Override
    @Transactional
    public void run(String... args) {
        int patched = 0;
        for (BenchmarkRun run : runRepository.findAll()) {
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PENDING) continue;
            run.setStatus(RunStatus.FAILED);
            if (run.getFinishedAt() == null) run.setFinishedAt(Instant.now());
            for (BenchmarkResult result : run.getResults()) {
                if (result.getStatus() == RunStatus.RUNNING || result.getStatus() == RunStatus.PENDING) {
                    result.setStatus(RunStatus.FAILED);
                    if (result.getErrorMessage() == null) result.setErrorMessage(STALE_MESSAGE);
                    if (result.getFinishedAt() == null) result.setFinishedAt(Instant.now());
                }
            }
            runRepository.save(run);
            patched++;
        }
        if (patched > 0) log.info("Marked {} stale run(s) as FAILED on startup", patched);
    }
}
