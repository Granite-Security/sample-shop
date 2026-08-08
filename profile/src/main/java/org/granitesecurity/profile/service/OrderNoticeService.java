package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.UserMessage;
import org.granitesecurity.profile.repository.ProcessedOrderNoticeRepository;
import org.granitesecurity.profile.repository.UserMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Turns a "someone placed an order" fact from shop into an in-app message to admin.
 *
 * <p>This is the one message in the system the system writes itself. Everything else in
 * {@code user_message} is one user writing to another (docs/users/messaging.md §1), and
 * that document's rule — messaging uses no Kafka — still holds for the send path a
 * browser drives. What arrives here is not a user's words being routed through a broker;
 * it is a domain fact that profile renders into a sentence, which is why the wording
 * lives in this service and not on the topic.
 *
 * <p>The sender is a reserved username, not a real account. Admin replying to it writes
 * a row nobody reads — an accepted rough edge for now, and the reason the body names the
 * shopper: admin can start a real thread with them from the recipient search.
 */
@Service
public class OrderNoticeService {

    private static final Logger log = LoggerFactory.getLogger(OrderNoticeService.class);

    private static final String SUBJECT = "New order";

    private final ProcessedOrderNoticeRepository processedRepository;
    private final UserMessageRepository userMessageRepository;
    private final String recipient;
    private final String sender;

    public OrderNoticeService(ProcessedOrderNoticeRepository processedRepository,
                              UserMessageRepository userMessageRepository,
                              @Value("${profile.order-notices.recipient:admin}") String recipient,
                              @Value("${profile.order-notices.sender:system}") String sender) {
        this.processedRepository = processedRepository;
        this.userMessageRepository = userMessageRepository;
        this.recipient = recipient;
        this.sender = sender;
    }

    /**
     * Claim first, write second. A crash between the two drops a notice; doing it the
     * other way round would duplicate one, and a missing courtesy message beats two.
     */
    public Mono<Void> notifyAdmin(String username, Long orderId) {
        return processedRepository.claim(orderId)
                .flatMap(claimed -> {
                    if (claimed == null || claimed == 0) {
                        log.debug("Order {} already announced, skipping redelivery", orderId);
                        return Mono.empty();
                    }
                    return writeMessage(username, orderId);
                })
                .then();
    }

    private Mono<Void> writeMessage(String username, Long orderId) {
        UserMessage message = new UserMessage();
        message.setSenderUsername(sender);
        message.setRecipientUsername(recipient);
        message.setSubject(SUBJECT);
        message.setBody(username + " placed an order.");
        message.setCreatedAt(Instant.now());
        return userMessageRepository.save(message)
                .doOnNext(saved -> log.info("Order {} announced to {} as message {}",
                        orderId, recipient, saved.getId()))
                .then();
    }
}
