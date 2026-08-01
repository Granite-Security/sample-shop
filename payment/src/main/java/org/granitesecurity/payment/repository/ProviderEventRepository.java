package org.granitesecurity.payment.repository;

import org.granitesecurity.payment.domain.ProviderEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProviderEventRepository extends ReactiveCrudRepository<ProviderEvent, UUID> {

    /** Dedupe lookup. Scoped by provider: the event id alone is unique only within one provider. */
    Mono<ProviderEvent> findByProviderAndProviderEventId(String provider, String providerEventId);
}
