package org.granitesecurity.notification.repository;

import org.granitesecurity.notification.domain.ProcessedEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends ReactiveCrudRepository<ProcessedEvent, UUID> {
}
