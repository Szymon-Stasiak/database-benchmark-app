package com.dbagnets.backend.engine.driver;

import com.dbagnets.backend.engine.registry.EntityIdRegistry.RegistryEntry;
import com.dbagnets.backend.engine.timing.TimedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class PerTargetLoop {

    private static final Logger log = LoggerFactory.getLogger(PerTargetLoop.class);

    private PerTargetLoop() {}

    @FunctionalInterface
    public interface TargetHandler {
        long execute(RegistryEntry entry) throws Exception;
    }

    public static TimedOperation run(List<RegistryEntry> targets,
                                      String opLabel,
                                      Function<RegistryEntry, String> displayId,
                                      TargetHandler handler) {
        SampledAccumulator acc = new SampledAccumulator(targets.size());
        long wireStart = System.nanoTime();
        for (int i = 0; i < targets.size(); i++) {
            RegistryEntry entry = targets.get(i);
            try {
                long start = System.nanoTime();
                long rows = handler.execute(entry);
                acc.sample(i, System.nanoTime() - start, rows);
            } catch (Exception ex) {
                log.warn("{} failed {}: {}", opLabel, displayId.apply(entry), ex.getMessage());
            }
        }
        return acc.finish(System.nanoTime() - wireStart);
    }

    public static TimedOperation runWithCascade(List<RegistryEntry> targets,
                                                  String opLabel,
                                                  Function<RegistryEntry, String> displayId,
                                                  Map<String, List<String>> cascadeAccumulator,
                                                  TargetHandler handler) {
        SampledAccumulator acc = new SampledAccumulator(targets.size());
        long wireStart = System.nanoTime();
        for (int i = 0; i < targets.size(); i++) {
            RegistryEntry entry = targets.get(i);
            try {
                long start = System.nanoTime();
                long rows = handler.execute(entry);
                acc.sample(i, System.nanoTime() - start, rows);
            } catch (Exception ex) {
                log.warn("{} failed {}: {}", opLabel, displayId.apply(entry), ex.getMessage());
            }
        }
        return acc.finishWithCascade(System.nanoTime() - wireStart, cascadeAccumulator);
    }
}
