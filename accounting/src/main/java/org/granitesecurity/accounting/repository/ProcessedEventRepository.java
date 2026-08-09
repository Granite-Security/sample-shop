package org.granitesecurity.accounting.repository;

import org.granitesecurity.accounting.domain.ProcessedEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends ReactiveCrudRepository<ProcessedEvent, String> {
}
