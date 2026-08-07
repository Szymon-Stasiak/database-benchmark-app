package com.dbagnets.backend.benchmark.setup.port;

import java.util.List;

import com.dbagnets.backend.infrastructure.scriptgen.ScriptCreatorRequest;
import com.dbagnets.backend.infrastructure.scriptgen.ScriptCreatorResponse;

public interface ScriptGenerationPort {

    ScriptCreatorResponse generate(
            String idea, int depth, List<ScriptCreatorRequest.TargetRequest> targets);
}
