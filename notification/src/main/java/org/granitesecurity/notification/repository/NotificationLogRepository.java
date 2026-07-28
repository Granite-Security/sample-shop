package org.granitesecurity.notification.repository;

import org.granitesecurity.notification.domain.NotificationLog;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationLogRepository extends ReactiveCrudRepository<NotificationLog, Long> {
}
