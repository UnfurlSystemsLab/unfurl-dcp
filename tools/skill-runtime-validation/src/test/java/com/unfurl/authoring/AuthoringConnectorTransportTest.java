package com.unfurl.authoring;

import com.sun.net.httpserver.HttpServer;
import com.unfurl.flow.connectors.*;
import com.unfurl.flow.components.connector.ConnectorRequestExecutor;
import com.unfurl.flow.components.toolresult.ToolResultGateExecutor;
import com.unfurl.flow.profiles.*;
import com.unfurl.flow.engine.dcp.DcpBrokerRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.unfurl.substrate.domain.*;
import com.unfurl.substrate.policy.ExecutionContext;
import com.unfurl.substrate.ports.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Wire integration: exercise native Flow connector/gate executors with a real controlled local gateway. */
class AuthoringConnectorTransportTest {
    /** Fixture Factory: create the exact host tenant context rather than trusting request arguments. */
    private ExecutionContext context(String tenant) {
        return new ExecutionContext(tenant, "fixture-principal", List.of(), List.of(), "trace", "request", Map.of(), Map.of());
    }

    /** Definition Factory: pin a bearer SecretRef without embedding token values into graph config. */
    private ConnectorDefinition definition(URI origin) {
        return new ConnectorDefinition("fixture-tenant", "authoring", ConnectorTransportKind.HTTP, origin,
                ConnectorAuthType.BEARER, Map.of(), Map.of("token", new SecretRef("env", "FIXTURE_TOKEN")),
                Map.of(), Duration.ofSeconds(2), null, true, Map.of());
    }

    /** Node Factory: project the actual request envelope used by generated native call nodes. */
    private NodeExecutionRequest request(String uses, Map<String, Object> config, Map<String, Object> input) {
        NodeDefinition node = new NodeDefinition("call", NodeType.ACTION, uses, "call", "fixture", config,
                Map.of(), Map.of(), List.of(), null, null, null, null, OnFailurePolicy.FAIL, Map.of(), null, false);
        return new NodeExecutionRequest("execution", node, input, Map.of());
    }

    /** Conformance: native connector and post-gate accept PASS while preserving host-only credentials. */
    @Test void nativeConnectorAndGateExecuteRealLocalHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/calls/fabric.catalog-query", exchange -> {
            calls.incrementAndGet();
            assertEquals("Bearer fixture-private-token", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"success\":true,\"output\":{\"status\":\"PASS\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var registry = new InMemoryConnectorRegistry(); registry.register(definition(origin));
            var transport = new AuthoringConnectorTransport("fixture-tenant", origin, ref -> "fixture-private-token", 4096);
            var executor = new ConnectorRequestExecutor(registry, transport);
            var result = executor.execute(request("connector.request", Map.of("connector", "authoring", "method", "POST",
                    "path", "/calls/fabric.catalog-query"), Map.of("body", Map.of("callId", "wire-1", "arguments", Map.of()))), context("fixture-tenant"));
            assertEquals(NodeExecutionResultStatus.COMPLETED, result.status());
            var gate = new ToolResultGateExecutor().execute(request("tool.result.gate", Map.of("expectedStatus", "PASS"),
                    Map.of("result", result.output())), context("fixture-tenant"));
            assertEquals(NodeExecutionResultStatus.COMPLETED, gate.status());
            assertEquals(1, calls.get());
            assertFalse(result.output().toString().contains("fixture-private-token"));
            var denied = executor.execute(request("connector.request", Map.of("connector", "authoring", "method", "POST",
                    "path", "/calls/fabric.catalog-query"), Map.of("body", Map.of())), context("other-tenant"));
            assertEquals(NodeExecutionResultStatus.FAILED, denied.status()); assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    /** Egress: host mismatch, credential injection and relative traversal fail before any network effect. */
    @Test void invalidScopeAndPathsAreRejected() {
        URI origin = URI.create("http://127.0.0.1:1");
        var transport = new AuthoringConnectorTransport("fixture-tenant", origin, ref -> "fixture-token", 4096);
        for (String path : List.of("//example.invalid/call", "/calls/../admin", "/calls/tool?token=x")) {
            var response = transport.execute(definition(origin), new ConnectorRequest("authoring", "POST", path,
                    Map.of(), Map.of(), Map.of(), null, null), context("fixture-tenant"));
            assertFalse(response.success()); assertFalse(response.retryable());
        }
        assertThrows(IllegalArgumentException.class, () -> new AuthoringConnectorTransport("fixture", URI.create("http://example.com"), ref -> "token", 128));
    }

    /** End-to-end local conformance: run a generated four-node workflow in Flow's actual embedded engine. */
    @Test void generatedWorkflowRunsThroughNativeFlowEngine() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean gap = new AtomicBoolean();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath()); exchange.getRequestBody().readAllBytes();
            byte[] bytes = ("{\"success\":true,\"output\":{\"status\":\"" + (gap.get() ? "GAP" : "PASS") + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var transport = new AuthoringConnectorTransport("fixture-tenant", origin, ref -> "fixture-token", 4096);
            var runtime = new ProfileLoader(Map.of(), transport, List.of(definition(origin)), DcpBrokerRuntime.inMemory())
                    .load(RuntimeProfile.embedded("controlled-authoring-test", List.of("connector.request", "tool.result.gate")));
            Path file = Path.of(System.getProperty("bundle.dir"), "flow", "fabric.catalog-query.workflow.json");
            WorkflowDefinition generated = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(file.toFile(), WorkflowDefinition.class);
            // Bind the generated logical connector name to this isolated host fixture.
            List<NodeDefinition> nodes = generated.nodes().stream().map(node -> {
                if (!"connector.request".equals(node.uses())) return node;
                Map<String, Object> config = new LinkedHashMap<>(node.config()); config.put("connector", "authoring");
                return new NodeDefinition(node.id(), node.type(), node.uses(), node.name(), node.description(), config,
                        node.input(), node.outputMapping(), node.dependencies(), node.condition(), node.loop(), node.saga(),
                        node.compensation(), node.onFailure(), node.retry(), node.timeout(), node.template());
            }).toList();
            WorkflowDefinition workflow = new WorkflowDefinition(generated.id(), generated.version(), generated.metadata(),
                    generated.inputSchema(), generated.outputSchema(), generated.policies(), generated.triggers(), nodes, generated.edges());
            var state = runtime.engine().start(workflow, Map.of("request", Map.of("callId", "native-1",
                    "arguments", Map.of("tenantId", "fixture-tenant"))), context("fixture-tenant"));
            assertEquals(WorkflowStatus.COMPLETED, state.status());
            assertEquals(List.of("/preflight/fabric.catalog-query", "/calls/fabric.catalog-query"), paths);
            paths.clear(); gap.set(true);
            var blocked = runtime.engine().start(workflow, Map.of("request", Map.of("callId", "native-gap",
                    "arguments", Map.of("tenantId", "fixture-tenant"))), context("fixture-tenant"));
            assertEquals(WorkflowStatus.FAILED, blocked.status());
            assertEquals(List.of("/preflight/fabric.catalog-query"), paths);
        } finally { server.stop(0); }
    }
}
