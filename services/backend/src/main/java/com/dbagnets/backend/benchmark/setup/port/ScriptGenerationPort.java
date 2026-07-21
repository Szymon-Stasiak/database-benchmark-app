package com.dbagnets.backend.benchmark.setup.port;

import com.dbagnets.backend.infrastructure.scriptgen.ScriptCreatorRequest;
import com.dbagnets.backend.infrastructure.scriptgen.ScriptCreatorResponse;

import java.util.List;

public interface ScriptGenerationPort {

    ScriptCreatorResponse generate(String idea, int depth, List<ScriptCreatorRequest.TargetRequest> targets);
}