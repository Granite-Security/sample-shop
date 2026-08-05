package org.granitesecurity.profile.repository;

import org.granitesecurity.profile.domain.UserMessage;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserMessageRepository extends ReactiveCrudRepository<UserMessage, Long> {

    @Query("""
            SELECT * FROM user_message
            WHERE recipient_username = :username AND recipient_deleted = FALSE
            ORDER BY created_at DESC
            LIMIT :size OFFSET :offset
            """)
    Flux<UserMessage> findInbox(String username, int size, long offset);

    @Query("""
            SELECT * FROM user_message
            WHERE sender_username = :username AND sender_deleted = FALSE
            ORDER BY created_at DESC
            LIMIT :size OFFSET :offset
            """)
    Flux<UserMessage> findSent(String username, int size, long offset);

    /**
     * The caller scope is part of the query, not a check applied to the result of a
     * findById — that is the difference between an authorization rule and an IDOR
     * (docs/users/messaging.md §5).
     */
    @Query("""
            SELECT * FROM user_message
            WHERE id = :id
              AND ((sender_username = :username AND sender_deleted = FALSE)
                OR (recipient_username = :username AND recipient_deleted = FALSE))
            """)
    Mono<UserMessage> findByIdForParticipant(Long id, String username);

    @Query("""
            SELECT count(*) FROM user_message
            WHERE recipient_username = :username AND recipient_deleted = FALSE AND read_at IS NULL
            """)
    Mono<Long> countUnread(String username);

    /**
     * Deleting is per-side and the row lives on until both parties have deleted it —
     * at which point nobody can reach it and it can go. Two statements rather than a
     * read-modify-write, so two tabs deleting at once cannot resurrect a flag.
     */
    @Modifying
    @Query("""
            UPDATE user_message SET
                sender_deleted = CASE WHEN sender_username = :username THEN TRUE ELSE sender_deleted END,
                recipient_deleted = CASE WHEN recipient_username = :username THEN TRUE ELSE recipient_deleted END
            WHERE id = :id AND (sender_username = :username OR recipient_username = :username)
            """)
    Mono<Long> markDeletedFor(Long id, String username);

    @Modifying
    @Query("DELETE FROM user_message WHERE id = :id AND sender_deleted = TRUE AND recipient_deleted = TRUE")
    Mono<Long> purgeIfDeletedByBoth(Long id);

    /** Only the recipient's read stamp is ever set, and only once. */
    @Modifying
    @Query("""
            UPDATE user_message SET read_at = now()
            WHERE id = :id AND recipient_username = :username AND read_at IS NULL
            """)
    Mono<Long> markRead(Long id, String username);
}
