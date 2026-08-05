package org.granitesecurity.profile.repository;

import org.granitesecurity.profile.domain.UserProfile;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserProfileRepository extends ReactiveCrudRepository<UserProfile, Long> {

    Mono<UserProfile> findByUsername(String username);

    /**
     * Deliberately a Flux: `email` has no UNIQUE constraint and one human can hold
     * two rows — a LOCAL one and a Google-sub one with the same address
     * (docs/users/blocking-users.md §2.1). Callers must pick; see
     * MessageService#resolveByEmail.
     */
    @Query("SELECT * FROM user_profile WHERE LOWER(email) = LOWER(:email) ORDER BY created_at")
    Flux<UserProfile> findAllByEmailIgnoreCase(String email);

    /**
     * Recipient search. Matches email so a colleague can be found by address, but the
     * DTO built from this must never expose it (docs/users/messaging.md §5) — matching
     * on a column and disclosing it are different things.
     */
    @Query("""
            SELECT * FROM user_profile
            WHERE username <> :caller
              AND (LOWER(username) LIKE LOWER(:prefix)
                OR LOWER(display_name) LIKE LOWER(:prefix)
                OR LOWER(email) LIKE LOWER(:prefix))
            ORDER BY username
            LIMIT :limit
            """)
    Flux<UserProfile> searchRecipients(String prefix, String caller, int limit);
}
