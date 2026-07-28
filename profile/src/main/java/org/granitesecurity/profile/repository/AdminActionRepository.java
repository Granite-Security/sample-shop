package org.granitesecurity.profile.repository;

import org.granitesecurity.profile.domain.AdminAction;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface AdminActionRepository extends ReactiveCrudRepository<AdminAction, Long> {

    Flux<AdminAction> findByTargetUserOrderByCreatedAtDesc(String targetUser);
}
