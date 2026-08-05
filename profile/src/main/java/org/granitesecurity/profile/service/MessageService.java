package org.granitesecurity.profile.service;

import org.granitesecurity.profile.client.IdentityAdminClient;
import org.granitesecurity.profile.domain.UserMessage;
import org.granitesecurity.profile.domain.UserProfile;
import org.granitesecurity.profile.dto.MessageResponse;
import org.granitesecurity.profile.dto.RecipientResponse;
import org.granitesecurity.profile.dto.SendMessageRequest;
import org.granitesecurity.profile.repository.UserMessageRepository;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * User-to-user messaging (docs/users/messaging.md).
 *
 * <p>No Kafka, no outbox, no event: a message is a row the sender writes and the
 * recipient queries, so the sender gets a real 201 or a real 404 rather than a 202
 * and a dead-letter (§1).
 *
 * <p><strong>Nothing here logs a message body.</strong> This is private
 * correspondence and the operator reading pod logs is not a party to it (§7.1).
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private static final int SUBJECT_MAX = 200;
    private static final int BODY_MAX = 4000;
    private static final int PREVIEW_MAX = 60;
    private static final int SEARCH_MIN_QUERY = 2;
    private static final int SEARCH_LIMIT = 10;
    private static final int PAGE_SIZE_MAX = 50;

    /**
     * A username that is nothing but digits is a Google `sub` sitting in the username
     * column, not something a human chose (docs/users/blocking-users.md §2.1).
     */
    private static final Pattern GOOGLE_SUB_USERNAME = Pattern.compile("\\d{10,}");

    private final UserMessageRepository userMessageRepository;
    private final UserProfileRepository userProfileRepository;
    private final IdentityAdminClient identityAdminClient;

    /**
     * Machine identities that hold a profile row because they once called a /me
     * endpoint with a client-credentials token. They are not people and must never
     * appear in a recipient picker (docs/users/messaging.md §3).
     */
    private final Set<String> excludedUsernames;

    public MessageService(UserMessageRepository userMessageRepository,
                          UserProfileRepository userProfileRepository,
                          IdentityAdminClient identityAdminClient,
                          @Value("${messaging.excluded-usernames:external-service}") String excluded) {
        this.userMessageRepository = userMessageRepository;
        this.userProfileRepository = userProfileRepository;
        this.identityAdminClient = identityAdminClient;
        this.excludedUsernames = Arrays.stream(excluded.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    // ---------------------------------------------------------------- sending

    public Mono<MessageResponse> send(String sender, SendMessageRequest req) {
        String subject = normaliseSubject(req.subject());
        String body = requireBody(req.body());

        return resolveRecipient(req.to())
                .flatMap(recipient -> {
                    if (recipient.getUsername().equals(sender)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "You cannot send a message to yourself"));
                    }
                    return rejectIfBlocked(recipient.getUsername()).thenReturn(recipient);
                })
                .flatMap(recipient -> {
                    UserMessage message = new UserMessage();
                    message.setSenderUsername(sender);
                    message.setRecipientUsername(recipient.getUsername());
                    message.setSubject(subject);
                    message.setBody(body);
                    message.setCreatedAt(Instant.now());
                    return userMessageRepository.save(message)
                            .doOnNext(saved -> log.info("Message {} sent from {} to {}",
                                    saved.getId(), sender, recipient.getUsername()))
                            .map(saved -> toResponse(saved, sender, recipient));
                });
    }

    /**
     * One input field, one resolver: an {@code @} means it is an email, anything else
     * is a username (docs/users/messaging.md §3).
     */
    private Mono<UserProfile> resolveRecipient(String to) {
        if (to == null || to.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A recipient is required");
        }
        String target = to.trim();
        Mono<UserProfile> found = target.contains("@")
                ? resolveByEmail(target)
                : userProfileRepository.findByUsername(target);

        return found.switchIfEmpty(Mono.error(new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No such user: " + target)));
    }

    /**
     * `email` has no UNIQUE constraint and one human can hold two rows with the same
     * address — a LOCAL one and a Google-sub one (docs/users/blocking-users.md §2.1).
     * Prefer the human-typeable username: it is the identity with a password and the
     * inbox they will actually open.
     *
     * <p>This tie-break is a heuristic papering over the duplicate-identity bug.
     * <strong>Delete it when that bug is fixed</strong> rather than preserving it.
     */
    private Mono<UserProfile> resolveByEmail(String email) {
        return userProfileRepository.findAllByEmailIgnoreCase(email)
                .collectList()
                .flatMap(candidates -> {
                    if (candidates.isEmpty()) {
                        return Mono.empty();
                    }
                    if (candidates.size() == 1) {
                        return Mono.just(candidates.get(0));
                    }
                    UserProfile chosen = candidates.stream()
                            .filter(p -> !isGoogleSubUsername(p.getUsername()))
                            .findFirst()
                            // All candidates are sub-shaped: the list is ordered by
                            // created_at, so the first is the oldest.
                            .orElse(candidates.get(0));
                    log.warn("Email {} matches {} profiles ({}); delivering to {}. "
                                    + "See docs/users/blocking-users.md 2.1 (duplicate identities).",
                            email, candidates.size(), usernamesOf(candidates), chosen.getUsername());
                    return Mono.just(chosen);
                });
    }

    /**
     * A blocked user cannot sign in, so a message to them would sit unread forever.
     *
     * <p>Fails <em>closed</em>: if auth-server cannot be reached we reject the send
     * rather than risk delivering into a disabled account (docs/users/messaging.md §6).
     * That does couple sending to auth-server's availability — the trade-off is
     * deliberate and is the first thing to revisit if it proves annoying.
     */
    private Mono<Void> rejectIfBlocked(String recipient) {
        return identityAdminClient.listUsers()
                .filter(user -> recipient.equals(user.username()))
                .next()
                .flatMap(user -> user.enabled()
                        ? Mono.empty()
                        : Mono.<Void>error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "That user is not accepting messages")))
                // An empty result means the recipient is not in auth-server's list at
                // all — a profile row with no identity behind it. Nothing to block,
                // and the profile lookup already established the row exists.
                .onErrorResume(e -> {
                    // The 403 above is the answer, not a failure to get one.
                    if (e instanceof ResponseStatusException) {
                        return Mono.error(e);
                    }
                    log.error("Could not check block state for {} — rejecting the send", recipient, e);
                    return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Cannot verify the recipient right now, please try again"));
                })
                .then();
    }

    // --------------------------------------------------------------- reading

    public Flux<MessageResponse> list(String username, String box, int page, int size) {
        int limit = Math.min(Math.max(size, 1), PAGE_SIZE_MAX);
        long offset = (long) Math.max(page, 0) * limit;
        boolean sent = "sent".equalsIgnoreCase(box);

        Flux<UserMessage> messages = sent
                ? userMessageRepository.findSent(username, limit, offset)
                : userMessageRepository.findInbox(username, limit, offset);

        return messages.flatMapSequential(message -> withCounterparty(message, username));
    }

    public Mono<Long> unreadCount(String username) {
        return userMessageRepository.countUnread(username);
    }

    /** Opening a message you received marks it read; opening one you sent does not. */
    public Mono<MessageResponse> get(Long id, String username) {
        return userMessageRepository.findByIdForParticipant(id, username)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Message not found")))
                .flatMap(message -> markRead(id, username).thenReturn(message))
                .flatMap(message -> {
                    if (username.equals(message.getRecipientUsername()) && message.getReadAt() == null) {
                        message.setReadAt(Instant.now());
                    }
                    return withCounterparty(message, username);
                });
    }

    /** A sender calling this is a no-op, not a 403 — they legitimately own the row. */
    public Mono<Void> markRead(Long id, String username) {
        return userMessageRepository.markRead(id, username).then();
    }

    public Mono<Void> delete(Long id, String username) {
        return userMessageRepository.markDeletedFor(id, username)
                .flatMap(updated -> updated == 0
                        ? Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"))
                        : userMessageRepository.purgeIfDeletedByBoth(id))
                .then();
    }

    // ------------------------------------------------------------ recipients

    /**
     * Matches on email so someone can be found by an address you already know, but the
     * DTO carries none — see RecipientResponse. The minimum query length is what stops
     * a single character enumerating the user table.
     */
    public Flux<RecipientResponse> searchRecipients(String query, String caller) {
        if (query == null || query.trim().length() < SEARCH_MIN_QUERY) {
            return Flux.empty();
        }
        String prefix = query.trim() + "%";

        return blockedUsernames()
                .flatMapMany(blocked -> userProfileRepository
                        .searchRecipients(prefix, caller, SEARCH_LIMIT)
                        .filter(profile -> !excludedUsernames.contains(profile.getUsername()))
                        .filter(profile -> !blocked.contains(profile.getUsername()))
                        .map(profile -> new RecipientResponse(
                                profile.getUsername(),
                                displayNameOf(profile),
                                ProfileService.effectiveAvatarUrl(profile))));
    }

    /**
     * Fails <em>open</em>, unlike the send path: a blocked user appearing in a picker
     * is cosmetic and the send that follows is still checked (§6).
     */
    private Mono<Set<String>> blockedUsernames() {
        return identityAdminClient.listUsers()
                .filter(user -> !user.enabled())
                .map(org.granitesecurity.profile.dto.AuthUser::username)
                .collect(Collectors.<String>toSet())
                .onErrorResume(e -> {
                    log.warn("Could not load block state for recipient search, showing all users", e);
                    return Mono.just(Set.of());
                });
    }

    // --------------------------------------------------------------- mapping

    /**
     * Resolves the <em>other</em> party's display name and avatar so a list of 20
     * messages does not become 20 lookups in the browser. A missing profile is not an
     * error: the counterparty may have been deleted since, and the message still reads.
     */
    private Mono<MessageResponse> withCounterparty(UserMessage message, String viewer) {
        boolean outgoing = viewer.equals(message.getSenderUsername());
        String counterparty = outgoing ? message.getRecipientUsername() : message.getSenderUsername();

        return userProfileRepository.findByUsername(counterparty)
                .map(profile -> toResponse(message, viewer, profile))
                .defaultIfEmpty(toResponse(message, viewer, null));
    }

    private MessageResponse toResponse(UserMessage message, String viewer, UserProfile counterparty) {
        boolean outgoing = viewer.equals(message.getSenderUsername());
        String counterpartyUsername = outgoing
                ? message.getRecipientUsername()
                : message.getSenderUsername();

        return new MessageResponse(
                message.getId(),
                message.getSenderUsername(),
                message.getRecipientUsername(),
                counterpartyUsername,
                counterparty != null ? displayNameOf(counterparty) : counterpartyUsername,
                counterparty != null ? ProfileService.effectiveAvatarUrl(counterparty) : null,
                message.getSubject(),
                message.getBody(),
                preview(message.getBody()),
                message.getReadAt() != null,
                message.getReadAt(),
                outgoing,
                message.getCreatedAt());
    }

    // -------------------------------------------------------------- validation

    /** Blank becomes null so "no subject" has exactly one representation (§5.1). */
    private static String normaliseSubject(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > SUBJECT_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Subject must be at most " + SUBJECT_MAX + " characters");
        }
        return trimmed;
    }

    private static String requireBody(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A message body is required");
        }
        if (trimmed.length() > BODY_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Message must be at most " + BODY_MAX + " characters");
        }
        return trimmed;
    }

    /** What the list shows when there is no subject — the same untrusted text, shorter. */
    private static String preview(String body) {
        String firstLine = body.lines().findFirst().orElse("").trim();
        return firstLine.length() <= PREVIEW_MAX
                ? firstLine
                : firstLine.substring(0, PREVIEW_MAX).trim() + "…";
    }

    private static String displayNameOf(UserProfile profile) {
        if (profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
            return profile.getDisplayName();
        }
        String full = ((profile.getFirstName() == null ? "" : profile.getFirstName()) + " "
                + (profile.getLastName() == null ? "" : profile.getLastName())).trim();
        return full.isEmpty() ? profile.getUsername() : full;
    }

    private static boolean isGoogleSubUsername(String username) {
        return username != null && GOOGLE_SUB_USERNAME.matcher(username).matches();
    }

    private static String usernamesOf(List<UserProfile> profiles) {
        return profiles.stream().map(UserProfile::getUsername).collect(Collectors.joining(", "));
    }
}
