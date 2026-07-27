package org.granitesecurity.profile.repository;

import org.granitesecurity.profile.domain.UserFile;
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
}
