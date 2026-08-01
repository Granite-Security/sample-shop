package org.granitesecurity.payment.handler;

import org.granitesecurity.payment.provider.PaymentProvider;
import org.granitesecurity.payment.provider.PaymentProviderRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class HealthHandler {

    private final PaymentProviderRegistry providers;

    public HealthHandler(PaymentProviderRegistry providers) {
        this.providers = providers;
    }

    public Mono<ServerResponse> health(ServerRequest request) {
        return ServerResponse.ok().bodyValue(Map.of("status", "UP"));
    }

    /**
     * Probes every enabled provider. Replaces {@code /actuator/health/stripe}, which
     * nothing referenced — no k8s probe, no runbook, no frontend — so the path was
     * renamed rather than aliased.
     *
     * <p>Reports DEGRADED, not DOWN, when a provider is unreachable: the service is
     * still serving reads and the outbox still drains, so failing a liveness probe
     * here would restart a pod that is doing its job.
     */
    public Mono<ServerResponse> providersHealth(ServerRequest request) {
        return Flux.fromIterable(providers.enabled())
                .concatMap(provider -> provider.health()
                        .map(health -> Map.entry(provider.name(), describe(provider, health))))
                .collectList()
                .flatMap(entries -> {
                    Map<String, Object> byProvider = new LinkedHashMap<>();
                    entries.forEach(e -> byProvider.put(e.getKey(), e.getValue()));
                    boolean allUp = entries.stream()
                            .allMatch(e -> "UP".equals(((Map<?, ?>) e.getValue()).get("status")));
                    return ServerResponse.ok().bodyValue(Map.of(
                            "status", allUp ? "UP" : "DEGRADED",
                            "providers", byProvider));
                });
    }

    private static Map<String, Object> describe(PaymentProvider provider,
                                                org.granitesecurity.payment.provider.ProviderHealth health) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", health.up() ? "UP" : "DEGRADED");
        detail.put("displayName", provider.displayName());
        detail.put("confirmationMode", provider.confirmationMode().name());
        detail.put("webhookEnabled", provider.webhookEnabled());
        detail.putAll(health.details());
        return detail;
    }
}
