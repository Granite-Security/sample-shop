package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.UserMessage;
import org.granitesecurity.profile.domain.UserProfile;
import org.granitesecurity.profile.dto.ContactRequest;
import org.granitesecurity.profile.repository.UserMessageRepository;
import org.granitesecurity.profile.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contact form (docs/users/messaging.md §11). What is worth pinning down here is
 * who the sender ends up being and when nothing is written at all — the parts a manual
 * check against the cluster would not distinguish from a working form.
 */
@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    @Mock
    private UserMessageRepository userMessageRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Captor
    private ArgumentCaptor<UserMessage> messageCaptor;

    private ContactService service() {
        return new ContactService(userMessageRepository, userProfileRepository, "manager");
    }

    private void managerExists() {
        UserProfile manager = new UserProfile();
        manager.setUsername("manager");
        when(userProfileRepository.findByUsername("manager")).thenReturn(Mono.just(manager));
    }

    private void saveEchoes() {
        when(userMessageRepository.save(any(UserMessage.class))).thenAnswer(inv -> {
            UserMessage m = inv.getArgument(0);
            m.setId(1L);
            return Mono.just(m);
        });
    }

    @Test
    void shouldFileAnAnonymousSubmissionUnderTheNameAndEmailGiven() {
        managerExists();
        saveEchoes();

        StepVerifier.create(service().submit(null,
                        new ContactRequest("Ada", "ada@example.com", "Hello", "Do you ship to CH?", null)))
                .expectNextCount(1)
                .verifyComplete();

        verify(userMessageRepository).save(messageCaptor.capture());
        UserMessage message = messageCaptor.getValue();
        assert message.getSenderUsername() == null : message.getSenderUsername();
        assert "Ada".equals(message.getSenderName()) : message.getSenderName();
        assert "ada@example.com".equals(message.getSenderEmail()) : message.getSenderEmail();
        assert "manager".equals(message.getRecipientUsername()) : message.getRecipientUsername();
    }

    /**
     * The sender is the token, never the body. A signed-in caller who types someone
     * else's name and address into the form must not get a row that claims to be from
     * them — which is the whole reason those two fields are dropped rather than stored
     * alongside the username.
     */
    @Test
    void shouldIgnoreNameAndEmailFromTheBodyWhenTheCallerIsSignedIn() {
        managerExists();
        saveEchoes();

        StepVerifier.create(service().submit("net.vrabie",
                        new ContactRequest("Someone Else", "spoof@example.com", null, "hi", null)))
                .expectNextCount(1)
                .verifyComplete();

        verify(userMessageRepository).save(messageCaptor.capture());
        UserMessage message = messageCaptor.getValue();
        assert "net.vrabie".equals(message.getSenderUsername()) : message.getSenderUsername();
        assert message.getSenderName() == null : message.getSenderName();
        assert message.getSenderEmail() == null : message.getSenderEmail();
    }

    /** Dropped, but answered as though it succeeded — telling a bot is telling it how. */
    @Test
    void shouldSilentlyDropASubmissionThatTrippedTheHoneypot() {
        StepVerifier.create(service().submit(null,
                        new ContactRequest("Bot", "bot@example.com", "cheap pills", "buy", "http://spam.example")))
                .expectNextCount(1)
                .verifyComplete();

        verify(userMessageRepository, never()).save(any(UserMessage.class));
    }

    @Test
    void shouldRejectAnAnonymousSubmissionWithNoWayToReplyToIt() {
        StepVerifier.create(service().submit(null, new ContactRequest("Ada", "  ", null, "hi", null)))
                .verifyErrorSatisfies(e -> {
                    assert e instanceof ResponseStatusException : e;
                    assert ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST : e;
                });

        verify(userMessageRepository, never()).save(any(UserMessage.class));
    }

    /**
     * A misconfigured recipient would otherwise swallow every message a customer ever
     * sends, with a 201 on each one.
     */
    @Test
    void shouldRefuseToWriteWhenTheConfiguredRecipientHasNoProfile() {
        when(userProfileRepository.findByUsername("manager")).thenReturn(Mono.empty());

        StepVerifier.create(service().submit(null,
                        new ContactRequest("Ada", "ada@example.com", null, "hi", null)))
                .verifyErrorSatisfies(e -> {
                    assert e instanceof ResponseStatusException : e;
                    assert ((ResponseStatusException) e).getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE : e;
                });

        verify(userMessageRepository, never()).save(any(UserMessage.class));
    }
}
