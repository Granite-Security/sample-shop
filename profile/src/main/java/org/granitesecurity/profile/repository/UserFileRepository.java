package org.granitesecurity.profile.repository;

import org.granitesecurity.profile.domain.UserFile;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserFileRepository extends ReactiveCrudRepository<UserFile, Long> {

    Flux<UserFile> findByUsernameOrderByCreatedAtDesc(String username);

    Mono<UserFile> findByIdAndUsername(Long id, String username);

    Mono<Void> deleteByIdAndUsername(Long id, String username);

    Mono<Boolean> existsByObjectKey(String objectKey);

    Mono<Long> countByUsername(String username);

    Mono<UserFile> findByUsernameAndContentHash(String username, String contentHash);

    /**
     * The public file listing for a handle (docs/profile/public-profile.md §11).
     *
     * <p>The join is what makes this safe: files are only reachable through a profile
     * that is itself published, so unpublishing hides them with no second flag to keep
     * in sync, and the block-unpublishes-you path covers blocked users for free.
     */
    @Query("""
            SELECT f.* FROM user_file f
              JOIN user_profile p ON p.username = f.username
             WHERE p.handle = :handle
               AND p.public_profile = true
               AND f.shared = true
             ORDER BY f.created_at DESC
            """)
    Flux<UserFile> findSharedByHandle(String handle);

    /** Targeted UPDATE, scoped by username so one user cannot publish another's file. */
    @Modifying
    @Query("UPDATE user_file SET shared = :shared WHERE id = :id AND username = :username")
    Mono<Integer> updateShared(Long id, String username, boolean shared);
}
