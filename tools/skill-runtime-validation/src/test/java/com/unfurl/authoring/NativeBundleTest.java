package com.unfurl.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.substrate.domain.*;
import com.unfurl.foundry.substrate.agent.*;
import com.unfurl.foundry.substrate.skill.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native conformance: generated resources must deserialize into actual runtime models and pass their validators. */
class NativeBundleTest {
    /** Validates native definitions only; this does not assert that provider/tool bindings are deployed. */
    @Test void generatedDefinitionsPassNativeValidators() throws Exception {
        Path root = Path.of(System.getProperty("bundle.dir"));
        assertTrue(Files.isDirectory(root), "generate the runtime bundle before running this module");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        int workflows = 0, agents = 0, skills = 0;
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".workflow.json")) {
                    new WorkflowDefinitionValidator().validate(mapper.readValue(file.toFile(), WorkflowDefinition.class)); workflows++;
                } else if (name.endsWith(".agent.yaml")) {
                    new AgentDefinitionValidator().validate(mapper.readValue(file.toFile(), AgentDefinition.class)); agents++;
                } else if (name.endsWith(".skill.json")) {
                    new SkillDefinitionValidator().validate(mapper.readValue(file.toFile(), SkillDefinition.class)); skills++;
                }
            }
        }
        assertTrue(workflows > 0); assertEquals(4, agents); assertEquals(4, skills);
    }
}
