package org.granitesecurity.balance.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    /**
     * Declared rather than auto-created, so the retention is a decision rather than
     * whatever the broker defaults to.
     *
     * <p>Thirty days, an order of magnitude longer than {@code shop.notifications} — and
     * the reasoning runs the opposite way. There, retention is a ceiling: a reset consumer
     * group re-announces whatever is retained, so a week of orders would land in admin's
     * inbox at once. Here it is a floor. These are the only source of gift issuance and of
     * the funding split, the accounting service is the consumer, and a fact that expires
     * before it is consumed is money that never reaches the books. A consumer outage
     * lasting longer than retention should not be able to silently lose a month of sales.
     *
     * <p>SEGMENT_MS is not decoration: retention only makes closed segments eligible for
     * deletion, and on a low-volume topic the active segment would otherwise stay open far
     * longer than the retention window (see notification's KafkaTopicConfig).
     *
     * <p>KafkaAdmin applies this at creation only and will not alter an existing topic.
     */
    @Bean
    NewTopic balanceEvents() {
        return TopicBuilder.name("balance.events")
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000")
                .config(TopicConfig.SEGMENT_MS_CONFIG, "86400000")
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }
}
