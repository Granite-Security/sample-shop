package org.granitesecurity.payment.service;

import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource;
import com.stripe.net.BaseAddress;
import com.stripe.net.LiveStripeResponseGetter;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import org.granitesecurity.payment.domain.OutboxEvent;
import org.granitesecurity.payment.domain.Payment;
import org.granitesecurity.payment.domain.PaymentStatus;
import org.granitesecurity.payment.domain.Refund;
import org.granitesecurity.payment.domain.RefundStatus;
import org.granitesecurity.payment.provider.PaymentProviderRegistry;
import org.granitesecurity.payment.provider.stripe.StripePaymentProvider;
import org.granitesecurity.payment.repository.OutboxRepository;
import org.granitesecurity.payment.repository.PaymentAttemptRepository;
import org.granitesecurity.payment.repository.PaymentRepository;
import org.granitesecurity.payment.repository.RefundRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceRefundTest {

    private static final Long ORDER_ID = 42L;

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private PaymentAttemptRepository attemptRepository;

    private PaymentService paymentService;
    private RecordingResponseGetter stripeApi;

    /** Static-mocking Stripe is thread-scoped, but the service calls Stripe on boundedElastic —
     *  so tests swap the global StripeResponseGetter seam instead. */
    @SuppressWarnings("unchecked")
    private static class RecordingResponseGetter implements StripeResponseGetter {
        final List<Type> requestedTypes = new CopyOnWriteArrayList<>();
        volatile RequestOptions lastOptions;
        StripeObject paymentIntentResult;
        StripeObject refundResult;

        @Override
        public <T extends StripeObject> T request(BaseAddress baseAddress, ApiResource.RequestMethod method,
                                                  String path, Map<String, Object> params, Type responseType,
                                                  RequestOptions options, ApiMode apiMode) {
            requestedTypes.add(responseType);
            lastOptions = options;
            if (responseType == com.stripe.model.PaymentIntent.class) {
                return (T) paymentIntentResult;
            }
            if (responseType == com.stripe.model.Refund.class) {
                return (T) refundResult;
            }
            return null;
        }

        @Override
        public InputStream requestStream(BaseAddress baseAddress, ApiResource.RequestMethod method, String path,
                                         Map<String, Object> params, RequestOptions options, ApiMode apiMode) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        // A real StripePaymentProvider over a real registry: the point of these tests is
        // the service's refund logic, and the SDK is still intercepted below, so stubbing
        // the port here would only prove the stub works.
        paymentService = new PaymentService(paymentRepository, outboxRepository, refundRepository, attemptRepository,
                new PaymentProviderRegistry(List.of(new StripePaymentProvider()), "USD"));
        stripeApi = new RecordingResponseGetter();
        ApiResource.setGlobalResponseGetter(stripeApi);
    }

    @AfterEach
    void tearDown() {
        ApiResource.setGlobalResponseGetter(new LiveStripeResponseGetter());
    }

    private Payment succeededPayment() {
        Payment payment = new Payment(ORDER_ID, new BigDecimal("10.00"), "USD", "stripe");
        payment.setStatus(PaymentStatus.SUCCEEDED.name());
        payment.setProviderPaymentId("pi_123");
        payment.setCreatedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        return payment;
    }

    private Refund refundWith(RefundStatus status, String stripeRefundId) {
        Refund refund = new Refund(ORDER_ID, null, new BigDecimal("10.00"));
        refund.setStatus(status.name());
        refund.setProviderRefundId(stripeRefundId);
        refund.setCreatedAt(Instant.now());
        refund.setUpdatedAt(Instant.now());
        return refund;
    }

    private com.stripe.model.PaymentIntent succeededIntent() {
        com.stripe.model.PaymentIntent intent = new com.stripe.model.PaymentIntent();
        intent.setId("pi_123");
        intent.setStatus("succeeded");
        return intent;
    }

    private com.stripe.model.Refund stripeRefund(String id, String status) {
        com.stripe.model.Refund refund = new com.stripe.model.Refund();
        refund.setId(id);
        refund.setStatus(status);
        return refund;
    }

    // --- processRefundRequested ---

    @Test
    void paymentMissing_completesEmptyWithoutStripeCall() {
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(paymentService.processRefundRequested(ORDER_ID))
                .verifyComplete();

        assertThat(stripeApi.requestedTypes).isEmpty();
        verifyNoInteractions(refundRepository, outboxRepository);
    }

    @Test
    void paymentNotSucceeded_completesEmptyWithoutStripeCall() {
        Payment payment = succeededPayment();
        payment.setStatus(PaymentStatus.CREATED.name());
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(payment));

        StepVerifier.create(paymentService.processRefundRequested(ORDER_ID))
                .verifyComplete();

        assertThat(stripeApi.requestedTypes).isEmpty();
        verifyNoInteractions(refundRepository, outboxRepository);
    }

    @Test
    void existingSucceededRefund_republishesEventWithoutStripeCall() {
        Payment payment = succeededPayment();
        Refund refund = refundWith(RefundStatus.SUCCEEDED, "re_existing");

        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(payment));
        when(refundRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(refund));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(paymentService.processRefundRequested(ORDER_ID))
                .verifyComplete();

        assertThat(stripeApi.requestedTypes).isEmpty();

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("PaymentRefunded");
        assertThat(event.getPayload()).contains("re_existing").contains("\"REFUNDED\"");

        verify(paymentRepository, never()).save(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    void happyPath_createsStripeRefundAndPublishesEvent() {
        Payment payment = succeededPayment();
        stripeApi.refundResult = stripeRefund("re_123", "succeeded");

        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(payment));
        when(refundRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.empty());
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(paymentService.processRefundRequested(ORDER_ID))
                .verifyComplete();

        assertThat(stripeApi.requestedTypes).containsExactly(com.stripe.model.Refund.class);

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository, times(2)).save(refundCaptor.capture());
        Refund saved = refundCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.SUCCEEDED.name());
        assertThat(saved.getProviderRefundId()).isEqualTo("re_123");
        assertThat(saved.getAmount()).isEqualByComparingTo("10.00");

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("PaymentRefunded");
        assertThat(event.getAggregateId()).isEqualTo(String.valueOf(ORDER_ID));
        assertThat(event.getPayload())
                .contains("\"orderId\":42")
                .contains("\"status\":\"REFUNDED\"")
                .contains("re_123")
                .contains("refundedAt");
    }

    // --- syncPaymentStatus refund reconciliation ---

    @Test
    void sync_pendingRefundWithStripeId_reconcilesToSucceeded() {
        Payment payment = succeededPayment();
        Refund refund = refundWith(RefundStatus.PENDING, "re_9");
        stripeApi.paymentIntentResult = succeededIntent();
        stripeApi.refundResult = stripeRefund("re_9", "succeeded");

        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(payment));
        when(refundRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(refund));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(paymentService.syncPaymentStatus(ORDER_ID))
                .expectNextMatches(dto -> PaymentStatus.REFUNDED.name().equals(dto.status())
                        && dto.refund() != null
                        && RefundStatus.SUCCEEDED.name().equals(dto.refund().status())
                        && "re_9".equals(dto.refund().stripeRefundId()))
                .verifyComplete();

        assertThat(stripeApi.requestedTypes)
                .containsExactly(com.stripe.model.PaymentIntent.class, com.stripe.model.Refund.class);

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.SUCCEEDED.name());

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("PaymentRefunded");
        assertThat(outboxCaptor.getValue().getPayload()).contains("re_9");
    }

    @Test
    void sync_succeededRefund_noRefundRetrieveAndNoChanges() {
        Payment payment = succeededPayment();
        Refund refund = refundWith(RefundStatus.SUCCEEDED, "re_done");
        stripeApi.paymentIntentResult = succeededIntent();

        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(payment));
        when(refundRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(refund));

        StepVerifier.create(paymentService.syncPaymentStatus(ORDER_ID))
                .expectNextMatches(dto -> dto.refund() != null
                        && RefundStatus.SUCCEEDED.name().equals(dto.refund().status()))
                .verifyComplete();

        assertThat(stripeApi.requestedTypes).containsExactly(com.stripe.model.PaymentIntent.class);
        verify(refundRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void sync_failedRefundWithoutStripeId_reattemptsCreateWithSameIdempotencyKey() {
        Payment payment = succeededPayment();
        Refund refund = refundWith(RefundStatus.FAILED, null);
        stripeApi.paymentIntentResult = succeededIntent();
        stripeApi.refundResult = stripeRefund("re_retry", "succeeded");

        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(payment));
        when(refundRepository.findByOrderId(ORDER_ID)).thenReturn(Mono.just(refund));
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(paymentService.syncPaymentStatus(ORDER_ID))
                .expectNextMatches(dto -> PaymentStatus.REFUNDED.name().equals(dto.status())
                        && dto.refund() != null
                        && RefundStatus.SUCCEEDED.name().equals(dto.refund().status())
                        && "re_retry".equals(dto.refund().stripeRefundId()))
                .verifyComplete();

        assertThat(stripeApi.requestedTypes)
                .containsExactly(com.stripe.model.PaymentIntent.class, com.stripe.model.Refund.class);
        assertThat(stripeApi.lastOptions.getIdempotencyKey()).isEqualTo("refund-order-" + ORDER_ID);

        ArgumentCaptor<Refund> refundCaptor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(refundCaptor.capture());
        Refund saved = refundCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.SUCCEEDED.name());
        assertThat(saved.getProviderRefundId()).isEqualTo("re_retry");

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.REFUNDED.name());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("PaymentRefunded");
        assertThat(outboxCaptor.getValue().getPayload()).contains("re_retry");
    }
}
