package org.granitesecurity.profile.repository;

import org.granitesecurity.profile.domain.UserProfile;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface UserProfileRepository extends ReactiveCrudRepository<UserProfile, Long> {

    Mono<UserProfile> findByUsername(String username);
}
