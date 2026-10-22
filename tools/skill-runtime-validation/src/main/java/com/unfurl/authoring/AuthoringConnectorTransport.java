package com.unfurl.authoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfurl.flow.connectors.*;
import com.unfurl.substrate.policy.ExecutionContext;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Function;

/** Adapter: bind Flow's existing ConnectorTransport port to the guarded local authoring gateway.
 * Host-selected tenant, connector URI and SecretRef resolver are immutable; graph inputs cannot
 * select a different host or inject authorization. No broker API or model dependency is introduced.
 */
public final class AuthoringConnectorTransport implements ConnectorTransport {
    private final String tenant;
    private final URI origin;
    private final Function<SecretRef, String> secrets;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final int limit;

    /** Constructor: pin the host binding and response bound; only the loopback development gateway is supported. */
    public AuthoringConnectorTransport(String tenant, URI origin, Function<SecretRef, String> secrets, int limit) {
        if (tenant == null || tenant.isBlank() || origin == null || secrets == null || limit < 1
                || !"http".equals(origin.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(origin.getHost())
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) {
            throw new IllegalArgumentException("invalid host authoring connector binding");
        }
        this.tenant = tenant; this.origin = origin; this.secrets = secrets; this.limit = limit;
    }

    /** Executes one bounded gateway call with host credentials; gateway journaling fences replayed call ids. */
    @Override public ConnectorResponse execute(ConnectorDefinition definition, ConnectorRequest request, ExecutionContext context) {
        try {
            if (context == null || !tenant.equals(context.tenantId()) || !tenant.equals(definition.tenantId())
                    || !origin.equals(definition.baseUrl()) || definition.authType() != ConnectorAuthType.BEARER
                    || !"POST".equals(request.method()) || !request.path().matches("/(calls|preflight)/[a-z][a-z0-9.-]+")
                    || !request.queryParams().isEmpty() || !request.headers().isEmpty()) {
                return ConnectorResponse.failure("AUTHORING_SCOPE_DENIED", "authoring connector scope denied", false);
            }
            SecretRef reference = definition.credentialRefs().get("token");
            if (reference == null) return ConnectorResponse.failure("AUTHORING_SECRET_REQUIRED", "gateway credential required", false);
            String token = secrets.apply(reference);
            if (token == null || token.isBlank() || token.contains("\r") || token.contains("\n")) {
                return ConnectorResponse.failure("AUTHORING_SECRET_REQUIRED", "gateway credential unavailable", false);
            }
            Duration timeout = definition.timeout() == null ? Duration.ofSeconds(30) : definition.timeout();
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
                return ConnectorResponse.failure("AUTHORING_TIMEOUT_INVALID", "invalid timeout", false);
            }
            HttpRequest http = HttpRequest.newBuilder(origin.resolve(request.path())).timeout(timeout)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(request.body()))).build();
            CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(http, info -> new BoundedBody(limit));
            HttpResponse<byte[]> response;
            try { response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (Exception error) { pending.cancel(true); throw error; }
            if (response.statusCode() != 200) return ConnectorResponse.failure("AUTHORING_GATEWAY_REJECTED", "gateway rejected call", false);
            Map<String, Object> body = mapper.readValue(response.body(), new TypeReference<>() {});
            return ConnectorResponse.success(response.statusCode(), body);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return ConnectorResponse.failure("AUTHORING_INTERRUPTED", "call interrupted; inspect journal before retry", false);
        } catch (Exception error) {
            return ConnectorResponse.failure("AUTHORING_CALL_UNCERTAIN", "call failed; inspect gateway journal before retry", false);
        }
    }

    /** Subscriber Strategy: cap bytes before buffering and cancel upstream when the response exceeds its bound. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        /** Constructor: retain the host-selected per-response byte ceiling. */
        private BoundedBody(int limit) { this.limit = limit; }
        /** Exposes completion only after all bounded body bytes have arrived. */
        @Override public CompletionStage<byte[]> getBody() { return body; }
        /** Backpressure: request one publisher batch at a time. */
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        /** Consumes bounded buffers without allocating for an oversized response. */
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new IllegalStateException("response bound")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        /** Preserve transport failure; never manufacture an empty successful body. */
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        /** Complete the immutable body bytes for schema parsing. */
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
