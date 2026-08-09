package org.granitesecurity.accounting.service;

import org.granitesecurity.accounting.repository.FactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Retries facts whose prerequisites had not arrived when they did (§6).
 *
 * <p>Out-of-order delivery across four topics is guaranteed: a delivery can be consumed
 * before the order it delivers. Rather than block the consumer or post half a movement,
 * such a fact is stored UNPOSTED and picked up here once the missing fact lands.
 *
 * <p><b>It never gives up.</b> There is no attempt limit and no age cutoff — a fact that
 * cannot post is money that has not reached the books, and dropping it would make the
 * books quietly wrong instead of visibly incomplete. Persistent failures surface on
 * {@code /reconcile} as an unposted count, where a human can see them.
 */
@Component
public class UnpostedFactSweeper {

    private static final Logger log = LoggerFactory.getLogger(UnpostedFactSweeper.class);

    @Value("${accounting.sweep.batch-size:200}")
    private int batchSize;

    private final FactRepository factRepository;
    private final FactIngestService ingestService;

    public UnpostedFactSweeper(FactRepository factRepository, FactIngestService ingestService) {
        this.factRepository = factRepository;
        this.ingestService = ingestService;
    }

    @Scheduled(fixedDelayString = "${accounting.sweep.interval:30000}")
    void run() {
        sweep().subscribe();
    }

    Mono<Void> sweep() {
        return factRepository.findUnposted(batchSize)
                // Oldest first and strictly in order: a chain of order -> payment ->
                // delivery resolves in one pass, and the later fact cannot be attempted
                // before the earlier one it depends on.
                .concatMap(fact -> ingestService.postIfPossible(fact)
                        .onErrorResume(e -> {
                            log.warn("Fact {} still unposted: {}", fact.getId(), e.getMessage());
                            return Mono.empty();
                        }))
                .then()
                .onErrorResume(e -> {
                    log.error("Unposted-fact sweep failed", e);
                    return Mono.empty();
                });
    }
}
