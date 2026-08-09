package org.granitesecurity.accounting.config;

import org.apache.kafka.common.TopicPartition;
import org.granitesecurity.accounting.consumer.MalformedEventException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens when a listener throws — and the answer here is different from shop's on
 * purpose.
 *
 * <p>shop retries four times and then dead-letters, which is right for a service that can
 * carry on without one record. These are the books: a record that is skipped is money that
 * never gets counted, and nothing downstream would ever notice. So the backoff has
 * <b>no attempt limit</b>. A database outage or a bug in a posting rule stalls the
 * partition and keeps retrying, which is loud, visible and recoverable — the alternative
 * is quiet, permanent and wrong.
 *
 * <p>The one exception is a message that will not parse, which will not parse on the
 * hundredth attempt either. Those are dead-lettered immediately so they cannot stall a
 * partition forever, and the {@code .DLT} topic is the record that they existed.
 */
@Configuration
public class KafkaConfig {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // No setMaxAttempts: ExponentialBackOff retries until maxElapsedTime, which
        // defaults to unbounded. That is the intent — see the class comment.
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(60_000L);

        // Partition -1 lets Kafka choose. The default sends a dead record to the same
        // partition number it came from, which fails outright if the .DLT topic was
        // auto-created with fewer partitions than the source topic.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
