package org.granitesecurity.profile.service;

import org.granitesecurity.profile.domain.UserMessage;
import org.granitesecurity.profile.repository.ProcessedOrderNoticeRepository;
import org.granitesecurity.profile.repository.UserMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderNoticeServiceTest {

    @Mock
    private ProcessedOrderNoticeRepository processedRepository;

    @Mock
    private UserMessageRepository userMessageRepository;

    @Captor
    private ArgumentCaptor<UserMessage> messageCaptor;

    private OrderNoticeService service() {
        return new OrderNoticeService(processedRepository, userMessageRepository, "admin", "system");
    }

    @Test
    void shouldMessageAdminOnTheFirstDeliveryOfAnOrder() {
        when(processedRepository.claim(42L)).thenReturn(Mono.just(1L));
        when(userMessageRepository.save(any(UserMessage.class))).thenAnswer(inv -> {
            UserMessage m = inv.getArgument(0);
            m.setId(7L);
            return Mono.just(m);
        });

        StepVerifier.create(service().notifyAdmin("net.vrabie", 42L)).verifyComplete();

        verify(userMessageRepository).save(messageCaptor.capture());
        UserMessage message = messageCaptor.getValue();
        assert "admin".equals(message.getRecipientUsername()) : message.getRecipientUsername();
        assert "system".equals(message.getSenderUsername()) : message.getSenderUsername();
        assert message.getBody().contains("net.vrabie") : message.getBody();
    }

    /**
     * The whole point of the claim table: shop.notifications is at-least-once, and a
     * redelivered record must not put a second identical message in admin's inbox.
     */
    @Test
    void shouldWriteNothingWhenTheOrderWasAlreadyAnnounced() {
        when(processedRepository.claim(42L)).thenReturn(Mono.just(0L));

        StepVerifier.create(service().notifyAdmin("net.vrabie", 42L)).verifyComplete();

        verify(userMessageRepository, never()).save(any());
    }

    /**
     * ON CONFLICT DO NOTHING affecting no rows can come back as an empty Mono rather
     * than a zero, depending on the driver — that must read as "already claimed", not
     * as a reason to skip the guard.
     */
    @Test
    void shouldWriteNothingWhenTheClaimReturnsEmpty() {
        when(processedRepository.claim(42L)).thenReturn(Mono.empty());

        StepVerifier.create(service().notifyAdmin("net.vrabie", 42L)).verifyComplete();

        verify(userMessageRepository, never()).save(any());
    }
}
