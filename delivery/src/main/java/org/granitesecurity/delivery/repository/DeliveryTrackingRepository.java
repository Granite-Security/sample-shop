package org.granitesecurity.delivery.repository;

import org.granitesecurity.delivery.domain.DeliveryTracking;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Repository
public interface DeliveryTrackingRepository extends R2dbcRepository<DeliveryTracking, UUID> {
    Flux<DeliveryTracking> findByDeliveryIdOrderByTimestampDesc(UUID deliveryId);
}
