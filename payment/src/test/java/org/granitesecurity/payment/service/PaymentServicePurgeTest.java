package org.granitesecurity.payment.service;

import org.granitesecurity.payment.provider.PaymentProviderRegistry;
import org.granitesecurity.payment.provider.stripe.StripePaymentProvider;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.granitesecurity.payment.repository.PaymentRepository;
import org.granitesecurity.payment.repository.RefundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServicePurgeTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private RefundRepository refundRepository;

    private PaymentService service() {
        return new PaymentService(paymentRepository, outboxRepository, refundRepository,
                new PaymentProviderRegistry(java.util.List.of(new StripePaymentProvider()), "USD"));
    }

    @Test
    void deletesRefundsAndPaymentsForTheGivenOrders() {
        when(refundRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(0L));
        when(paymentRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(2L));

        StepVerifier.create(service().purgeOrders(List.of(1L, 2L))).verifyComplete();

        verify(refundRepository).deleteByOrderIdIn(List.of(1L, 2L));
        verify(paymentRepository).deleteByOrderIdIn(List.of(1L, 2L));
    }

    @Test
    void anEmptyOrderIdListTouchesNothing() {
        StepVerifier.create(service().purgeOrders(List.of())).verifyComplete();

        verify(paymentRepository, never()).deleteByOrderIdIn(anyCollection());
        verify(refundRepository, never()).deleteByOrderIdIn(anyCollection());
    }

    // A purge is a local cleanup, not a domain fact anyone downstream reacts
    // to — it must not put anything on payment's outbox. (stripe_event, the
    // other table §6 protects, is not reachable from here at all: PaymentService
    // has no StripeEventRepository dependency to delete through.)
    @Test
    void publishesNoOutboxEvent() {
        when(refundRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(0L));
        when(paymentRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(1L));

        StepVerifier.create(service().purgeOrders(List.of(1L))).verifyComplete();

        verifyNoInteractions(outboxRepository);
    }

    // Deleting rows that are already gone is a no-op, which is why the plan
    // needs no dedupe table for this event (§8 Phase 3).
    @Test
    void isIdempotentWhenTheRowsAreAlreadyGone() {
        when(refundRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(0L));
        when(paymentRepository.deleteByOrderIdIn(anyCollection())).thenReturn(Mono.just(0L));

        StepVerifier.create(service().purgeOrders(List.of(1L))).verifyComplete();
        StepVerifier.create(service().purgeOrders(List.of(1L))).verifyComplete();
    }
}
