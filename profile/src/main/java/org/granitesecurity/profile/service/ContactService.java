package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.UserMessage;
import org.granitesecurity.profile.dto.ContactRequest;
import org.granitesecurity.profile.dto.ContactResponse;
import org.granitesecurity.profile.repository.UserMessageRepository;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * The public contact form (docs/users/messaging.md §11).
 *
 * <p>Same store, same table, same inbox as user-to-user messaging — and for the same
 * reason (§1, D1): the visitor is standing there waiting to be told their message
 * arrived. A 202 and an outbox would turn "we could not reach anyone" into silence.
 * No Kafka is involved and none should be added.
 *
 * <p>This is the only write path into {@code user_message} that an unauthenticated
 * caller can reach, so the two things it must never do are take a recipient from the
 * request and take a sender identity from the request body. The recipient is
 * configuration; the sender is the JWT subject or nothing at all.
 *
 * <p><strong>Nothing here logs a message body</strong> — §7.1 applies to a stranger's
 * words exactly as it does to a user's.
 */
@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private static final int NAME_MAX = 120;
    private static final int EMAIL_MAX = 255;
    private static final int SUBJECT_MAX = 200;
    private static final int BODY_MAX = 4000;

    /**
     * Deliberately permissive. This is a reply-to hint for a human, not an
     * authentication factor — the only thing worth rejecting is input that plainly
     * is not an address, and anything stricter starts refusing valid ones.
     */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final UserMessageRepository userMessageRepository;
    private final UserProfileRepository userProfileRepository;
    private final String recipient;

    public ContactService(UserMessageRepository userMessageRepository,
                          UserProfileRepository userProfileRepository,
                          @Value("${profile.contact.recipient:manager}") String recipient) {
        this.userMessageRepository = userMessageRepository;
        this.userProfileRepository = userProfileRepository;
        this.recipient = recipient;
    }

    /**
     * @param sender the authenticated username, or null when nobody was signed in.
     */
    public Mono<ContactResponse> submit(String sender, ContactRequest req) {
        // Deferred so a validation failure arrives as an error signal rather than as a
        // throw out of a method whose signature promises a Mono. Reactor would catch it
        // either way inside the handler's flatMap; this makes the method honest on its
        // own, and testable without a try/catch around the subscribe.
        return Mono.defer(() -> doSubmit(sender, req));
    }

    private Mono<ContactResponse> doSubmit(String sender, ContactRequest req) {
        if (isHoneypotTripped(req)) {
            // Answered as if it worked. Telling a bot it was detected only tells it
            // which field to leave alone next time (§11.1).
            log.info("Dropping contact submission: honeypot field was filled in");
            return Mono.just(ContactResponse.received());
        }

        String subject = normaliseSubject(req.subject());
        String body = requireBody(req.body());
        String name = sender != null ? null : requireName(req.name());
        String email = sender != null ? null : requireEmail(req.email());

        // The recipient is a config value, and a typo in it would silently black-hole
        // every message a customer ever sends. Fail loudly at submit time instead.
        return userProfileRepository.findByUsername(recipient)
                .switchIfEmpty(Mono.error(() -> {
                    log.error("Contact recipient '{}' has no profile row — check profile.contact.recipient",
                            recipient);
                    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Nobody is available to receive messages right now, please try again later");
                }))
                .flatMap(manager -> {
                    UserMessage message = new UserMessage();
                    message.setSenderUsername(sender);
                    message.setSenderName(name);
                    message.setSenderEmail(email);
                    message.setRecipientUsername(manager.getUsername());
                    message.setSubject(subject);
                    message.setBody(body);
                    message.setCreatedAt(Instant.now());
                    return userMessageRepository.save(message);
                })
                .doOnNext(saved -> log.info("Contact message {} from {} delivered to {}",
                        saved.getId(), sender != null ? sender : "an anonymous visitor", recipient))
                .thenReturn(ContactResponse.received());
    }

    /**
     * The hidden field is not a checkbox: a browser that autofills it, or a user with a
     * password manager that guesses at it, has still tripped it. That is the trade — a
     * rare false positive against most naive bots, and nothing against a targeted one
     * (§11.1). A per-sender cap is still the real answer and is still owed (§7.3).
     */
    private static boolean isHoneypotTripped(ContactRequest req) {
        return req.website() != null && !req.website().isBlank();
    }

    // Unlike MessageService, a blank subject here is not an error and not "no subject":
    // a contact form with nothing in the subject line is the common case, so the inbox
    // preview falls back to the body exactly as it does for a user-to-user message.
    private static String normaliseSubject(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return trimToMax(raw, SUBJECT_MAX, "Subject");
    }

    private static String requireBody(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A message is required");
        }
        return trimToMax(trimmed, BODY_MAX, "Message");
    }

    private static String requireName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A name is required");
        }
        return trimToMax(trimmed, NAME_MAX, "Name");
    }

    private static String requireEmail(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        // Required, not optional: without it a reply is impossible and the submission
        // is a note in a bottle.
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An email address is required");
        }
        if (!EMAIL.matcher(trimmed).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That does not look like an email address");
        }
        return trimToMax(trimmed, EMAIL_MAX, "Email address");
    }

    private static String trimToMax(String raw, int max, String field) {
        String trimmed = raw.trim();
        if (trimmed.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be at most " + max + " characters");
        }
        return trimmed;
    }
}
