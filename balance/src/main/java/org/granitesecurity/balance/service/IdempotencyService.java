package org.granitesecurity.balance.service;

import tools.jackson.databind.ObjectMapper;
import org.granitesecurity.balance.domain.Idempotency;
import org.granitesecurity.balance.repository.IdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Makes a keyed money movement run at most once (D5).
 *
 * <p>The key is <b>optional</b>, following Stripe: supply one and a retry replays
 * the original answer; omit one and you get no protection, which is the caller's
 * choice to make. Requiring it would make the API tiresome to drive by hand for no
 * safety gain the caller cannot already opt into.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRepository idempotencyRepository;
    /** Jackson 3 (tools.jackson) — the one Spring Boot 4 auto-configures. */
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param key    caller-supplied, may be null or blank
     * @param type   how to deserialise a replayed response
     * @param action the movement, run only if this key has not been seen
     */
    public <T> Mono<T> once(String key, Class<T> type, Mono<T> action) {
        if (key == null || key.isBlank()) {
            return action;
        }
        String trimmed = key.trim();

        return idempotencyRepository.findById(trimmed)
                .map(existing -> replay(existing, type))
                .doOnNext(r -> log.info("Replayed idempotent response for key {}", trimmed))
                .switchIfEmpty(Mono.defer(() -> action.flatMap(result -> store(trimmed, result).thenReturn(result))
                        // Two identical requests in flight at once: the second loses
                        // the insert and reads what the first wrote, rather than
                        // reporting a conflict for something that did succeed.
                        .onErrorResume(DuplicateKeyException.class, e -> idempotencyRepository.findById(trimmed)
                                .map(existing -> replay(existing, type)))));
    }

    private <T> Mono<Void> store(String key, T result) {
        Idempotency row = new Idempotency();
        row.setKey(key);
        row.setTransferId(transferIdOf(result));
        row.setResponse(write(result));
        row.setCreatedAt(Instant.now());
        return idempotencyRepository.save(row).then();
    }

    private <T> T replay(Idempotency row, Class<T> type) {
        try {
            return objectMapper.readValue(row.getResponse(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Stored idempotent response is unreadable: " + row.getKey(), e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise idempotent response", e);
        }
    }

    /** Best-effort: the column is for humans reading the table, not for logic. */
    private static UUID transferIdOf(Object result) {
        try {
            var accessor = result.getClass().getMethod("transferId");
            Object value = accessor.invoke(result);
            return value == null ? new UUID(0, 0) : UUID.fromString(value.toString());
        } catch (Exception e) {
            return new UUID(0, 0);
        }
    }
}
