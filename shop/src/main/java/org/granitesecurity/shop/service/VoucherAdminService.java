package org.granitesecurity.shop.service;

import org.granitesecurity.shop.domain.Voucher;
import org.granitesecurity.shop.dto.CreateVoucherRequest;
import org.granitesecurity.shop.dto.VoucherResponse;
import org.granitesecurity.shop.repository.VoucherRedemptionRepository;
import org.granitesecurity.shop.repository.VoucherRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Creating and withdrawing vouchers — separated from {@link VoucherService} the way
 * {@code PackagingAdminService} is from {@code PackagingService}: one prices carts on
 * the shopper's path, the other changes what future shoppers are charged.
 */
@Service
public class VoucherAdminService {

    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository redemptionRepository;

    public VoucherAdminService(VoucherRepository voucherRepository,
                               VoucherRedemptionRepository redemptionRepository) {
        this.voucherRepository = voucherRepository;
        this.redemptionRepository = redemptionRepository;
    }

    public Flux<VoucherResponse> list() {
        return voucherRepository.findAllNewestFirst().flatMapSequential(this::withRedemptions);
    }

    public Mono<VoucherResponse> get(Long id) {
        return voucherRepository.findById(id)
                .switchIfEmpty(Mono.error(notFound(id)))
                .flatMap(this::withRedemptions);
    }

    public Mono<VoucherResponse> create(CreateVoucherRequest request, String createdBy) {
        if (request == null || VoucherService.isBlank(request.code())) {
            return Mono.error(new ShopException("A voucher needs a code"));
        }
        if (request.percentOff() == null || request.percentOff() < 1 || request.percentOff() > 100) {
            return Mono.error(new ShopException("percentOff must be between 1 and 100"));
        }
        if (request.validUntil() == null) {
            return Mono.error(new ShopException(
                    "A voucher needs an expiry date; one without it is a permanent price cut"));
        }
        Instant validFrom = request.validFrom() != null ? request.validFrom() : Instant.now();
        if (!request.validUntil().isAfter(validFrom)) {
            return Mono.error(new ShopException("validUntil must be after validFrom"));
        }
        String code = VoucherService.normalise(request.code());

        // Checked here for the message, enforced by the unique index. The index is what
        // makes it true; this is what makes the 409 readable.
        return voucherRepository.findByCode(code)
                .flatMap(existing -> Mono.<VoucherResponse>error(new ShopException(
                        "A voucher with code " + code + " already exists",
                        HttpStatus.CONFLICT, "Duplicate voucher")))
                .switchIfEmpty(Mono.defer(() -> voucherRepository.save(new Voucher(
                                code, request.percentOff(), validFrom, request.validUntil(),
                                request.description(), createdBy))
                        .flatMap(this::withRedemptions)));
    }

    /**
     * Withdraws a voucher without deleting it (V13).
     *
     * <p>Orders reference it by code, the admin list has to keep showing what was
     * pulled and when, and this is the one lever that works instantly if a code leaks.
     * Placed orders keep their discount: what they were charged is snapshotted (V5).
     */
    public Mono<VoucherResponse> revoke(Long id) {
        return voucherRepository.findById(id)
                .switchIfEmpty(Mono.error(notFound(id)))
                .flatMap(voucher -> {
                    if (voucher.getRevokedAt() != null) {
                        return Mono.just(voucher);
                    }
                    voucher.setRevokedAt(Instant.now());
                    return voucherRepository.save(voucher);
                })
                .flatMap(this::withRedemptions);
    }

    private Mono<VoucherResponse> withRedemptions(Voucher voucher) {
        return redemptionRepository.countByVoucherId(voucher.getId())
                .defaultIfEmpty(0L)
                .map(count -> new VoucherResponse(
                        voucher.getId(), voucher.getCode(), voucher.getPercentOff(),
                        voucher.getValidFrom(), voucher.getValidUntil(), voucher.getRevokedAt(),
                        voucher.getDescription(), voucher.getCreatedBy(), voucher.getCreatedAt(),
                        count, status(voucher, Instant.now())));
    }

    /** Derived on read, never stored — a stored status is one more thing that can drift. */
    private static String status(Voucher voucher, Instant now) {
        if (voucher.getRevokedAt() != null) {
            return "REVOKED";
        }
        if (voucher.getValidFrom() != null && now.isBefore(voucher.getValidFrom())) {
            return "SCHEDULED";
        }
        return now.isBefore(voucher.getValidUntil()) ? "ACTIVE" : "EXPIRED";
    }

    private static ShopException notFound(Long id) {
        return new ShopException("Voucher not found: " + id, HttpStatus.NOT_FOUND, "Not Found");
    }
}
