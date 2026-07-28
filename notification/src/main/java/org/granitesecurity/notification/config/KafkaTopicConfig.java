package org.granitesecurity.notification.config;

import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;

@Configuration
public class KafkaTopicConfig {

    /**
     * Declared here rather than left to Kafka's auto-creation, because auto-created
     * topics silently inherit the broker default of 7 days — and this topic carries
     * password reset tokens.
     *
     * <p>SEGMENT_MS is not decoration: RETENTION_MS only makes <em>closed</em> segments
     * eligible for deletion, never the active one, and the default segment roll is 7
     * days. On a low-volume topic like this one the active segment would stay open for
     * a week and the 1-hour retention would delete nothing. With a 10-minute roll,
     * actual deletion is bounded at roughly retention + segment ≈ 70 minutes.
     *
     * <p>KafkaAdmin applies these at creation only — it will not alter a topic that
     * already exists. If this topic was auto-created before this bean shipped, delete
     * it or fix it once with {@code kafka-configs --alter}.
     */
    @Bean
    NewTopic identityEvents() {
        return TopicBuilder.name("identity.events")
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "3600000")
                .config(TopicConfig.SEGMENT_MS_CONFIG, "600000")
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .build();
    }
}
