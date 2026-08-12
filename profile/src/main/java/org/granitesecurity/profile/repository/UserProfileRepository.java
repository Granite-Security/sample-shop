package org.granitesecurity.profile.repository;

import org.granitesecurity.profile.domain.UserProfile;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserProfileRepository extends ReactiveCrudRepository<UserProfile, Long> {

    Mono<UserProfile> findByUsername(String username);

    /**
     * Claims the row for a username, or does nothing if someone else got there first.
     *
     * <p>Replaces a findByUsername/switchIfEmpty(save) check-then-insert, which two
     * writers could both pass — the Kafka consumer provisioning from
     * {@code UserRegistered} and the user's own first {@code GET /api/profiles/me} —
     * leaving the loser with a DuplicateKeyException surfaced as a 500 on the profile
     * page. There are always two writers here: the event is fire-and-forget with no
     * outbox, so the HTTP path has to be able to create the profile itself.
     */
    @Modifying
    @Query("""
            INSERT INTO user_profile (username, avatar_source, created_at, updated_at)
            VALUES (:username, 'NONE', now(), now())
            ON CONFLICT (username) DO NOTHING
            """)
    Mono<Integer> insertIfAbsent(String username);

    /**
     * Fills in what a {@code UserRegistered} event knows, without disturbing anything
     * already present.
     *
     * <p>COALESCE in SQL rather than load-modify-save: {@code save()} writes every
     * column, so the previous version lost the email whenever the concurrent
     * Google-picture write had read the row first. Merging in SQL means the two
     * writers touch disjoint columns and neither can clobber the other. It also keeps
     * the original "only fill blanks, never overwrite" contract, so a redelivered
     * event cannot undo details the user has since edited.
     */
    @Modifying
    @Query("""
            INSERT INTO user_profile (username, email, first_name, last_name,
                                      avatar_source, created_at, updated_at)
            VALUES (:username, :email, :firstName, :lastName, 'NONE', now(), now())
            ON CONFLICT (username) DO UPDATE SET
                email      = COALESCE(user_profile.email,      EXCLUDED.email),
                first_name = COALESCE(user_profile.first_name, EXCLUDED.first_name),
                last_name  = COALESCE(user_profile.last_name,  EXCLUDED.last_name),
                updated_at = now()
            """)
    Mono<Integer> upsertFromRegistration(String username, String email,
                                         String firstName, String lastName);

    /**
     * Caches the Google picture, touching only the two columns it owns.
     *
     * <p>avatar_source flips to GOOGLE only on the first picture ever seen for this
     * user — a later NONE is a choice made on the profile page, and refreshing the
     * URL must not quietly undo it. The WHERE guard makes a repeat sign-in with an
     * unchanged picture write nothing at all.
     */
    @Modifying
    @Query("""
            UPDATE user_profile
               SET google_picture_url = :googlePictureUrl,
                   avatar_source = CASE
                       WHEN google_picture_url IS NULL AND avatar_source = 'NONE'
                       THEN 'GOOGLE' ELSE avatar_source END,
                   updated_at = now()
             WHERE username = :username
               AND google_picture_url IS DISTINCT FROM :googlePictureUrl
            """)
    Mono<Integer> syncGooglePicture(String username, String googlePictureUrl);

    /**
     * The public profile lookup (docs/profile/public-profile.md step 3).
     *
     * <p>{@code public_profile = true} is in the SQL, not in a service-side {@code if},
     * so there is no variant of this query that can return an unpublished row. A
     * findByHandle plus a check is one refactor away from leaking one.
     */
    @Query("SELECT * FROM user_profile WHERE handle = :handle AND public_profile = true")
    Mono<UserProfile> findPublishedByHandle(String handle);

    /**
     * Claims a handle, touching only the column it owns.
     *
     * <p>Targeted UPDATE rather than {@code save()} for the reason
     * {@link #syncGooglePicture} documents: a full-row write races the UserRegistered
     * consumer and writes back nulls over email/first/last.
     *
     * <p>Relies on uq_user_profile_handle to reject a taken handle — the caller maps the
     * DuplicateKeyException to a 409. A pre-flight SELECT would leave two users racing
     * for the same handle with a non-deterministic outcome.
     */
    @Modifying
    @Query("UPDATE user_profile SET handle = :handle, updated_at = now() WHERE username = :username")
    Mono<Integer> updateHandle(String username, String handle);

    /** Publishes or unpublishes. Same targeted-write reasoning as {@link #updateHandle}. */
    @Modifying
    @Query("""
            UPDATE user_profile
               SET public_profile = :publicProfile, updated_at = now()
             WHERE username = :username
            """)
    Mono<Integer> updateVisibility(String username, boolean publicProfile);

    /**
     * Admin force-unpublish, and the side effect of blocking a user
     * (docs/profile/public-profile.md D6). Local write: checking auth-server's block
     * state on every anonymous page view would put an unauthenticated path in front of
     * the identity service.
     */
    @Modifying
    @Query("""
            UPDATE user_profile
               SET public_profile = false, updated_at = now()
             WHERE username = :username AND public_profile = true
            """)
    Mono<Integer> unpublish(String username);

    /**
     * Releases a handle. A separate query rather than {@link #updateHandle} with a null
     * argument, which R2DBC cannot bind — the parameter has no inferrable type.
     */
    @Modifying
    @Query("UPDATE user_profile SET handle = NULL, updated_at = now() WHERE username = :username")
    Mono<Integer> clearHandle(String username);

    /** Availability check. Counts every row, published or not — the handle is reserved on set (D2). */
    @Query("SELECT COUNT(*) FROM user_profile WHERE handle = :handle AND username <> :username")
    Mono<Long> countHandleTakenByOthers(String handle, String username);

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
