package org.granitesecurity.shop.service;

import org.granitesecurity.shop.domain.Product;
import org.granitesecurity.shop.domain.Voucher;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.dto.VoucherPreviewRequest;
import org.granitesecurity.shop.dto.VoucherPreviewResponse;
import org.granitesecurity.shop.repository.ProductRepository;
import org.granitesecurity.shop.repository.VoucherRedemptionRepository;
import org.granitesecurity.shop.repository.VoucherRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Prices percentage vouchers (docs/finance/vouchers.md §6).
 *
 * <p>The one place a discount is computed. Both the preview endpoint and placement
 * come through {@link #evaluate}, so a shopper cannot be shown one number and
 * charged another — and the browser is never trusted with the arithmetic, matching
 * the rule already enforced for packaging.
 *
 * <p>Nothing here touches money: a voucher reduces a price, it does not move CHF,
 * and {@code balance} never learns one was applied (V1).
 */
@Service
public class VoucherService {

    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository redemptionRepository;
    private final ProductRepository productRepository;
    private final PackagingService packagingService;

    /** Must match {@code OrderService.shopCurrency}: the preview quotes what placement will charge. */
    @Value("${shop.currency:CHF}")
    private String shopCurrency;

    /**
     * The smallest total we can actually charge (§6.1).
     *
     * <p>A 100% voucher on a cart of bars — which need no box — prices the order at
     * zero, and no payment provider will take it: Stripe and PayPal both reject
     * amounts below roughly CHF 0.50, and a zero-amount balance spend is a ledger row
     * that moves nothing. A free-order flow would have to skip the provider entirely
     * and is a different feature; until it exists, the order is refused with a reason
     * the shopper can act on.
     */
    @Value("${shop.minimum-payable-total:0.50}")
    private BigDecimal minimumPayableTotal;

    public VoucherService(VoucherRepository voucherRepository,
                          VoucherRedemptionRepository redemptionRepository,
                          ProductRepository productRepository,
                          PackagingService packagingService) {
        this.voucherRepository = voucherRepository;
        this.redemptionRepository = redemptionRepository;
        this.productRepository = productRepository;
        this.packagingService = packagingService;
    }

    /**
     * Prices a code against a cart for checkout, storing and reserving nothing.
     *
     * <p>Reprices the cart from the catalogue rather than believing the amounts the
     * browser sent — the same rule the packaging quote follows, and the reason a
     * discount cannot be inflated by editing a request.
     *
     * <p>Not a reservation (V11): placement runs {@link #evaluate} again from scratch,
     * and a code can expire or be revoked in between.
     */
    public Mono<VoucherPreviewResponse> preview(VoucherPreviewRequest request, String username) {
        if (request == null || isBlank(request.code())) {
            return Mono.error(new ShopException("A voucher code is required"));
        }
        List<PlaceOrderRequest.LineItem> lines = request.items() == null ? List.of() : request.items();
        for (PlaceOrderRequest.LineItem line : lines) {
            if (line == null || line.productId() == null || line.quantity() <= 0) {
                return Mono.error(new ShopException(
                        "Each item must name a productId and a quantity greater than zero"));
            }
        }
        if (lines.isEmpty()) {
            return Mono.error(new ShopException("A voucher needs a cart to price"));
        }

        return productRepository.findAllById(lines.stream()
                        .map(PlaceOrderRequest.LineItem::productId).distinct().toList())
                .collectMap(Product::getId, Function.identity())
                .flatMap(products -> {
                    BigDecimal itemsTotal = BigDecimal.ZERO;
                    for (PlaceOrderRequest.LineItem line : lines) {
                        Product product = products.get(line.productId());
                        if (product == null) {
                            return Mono.<VoucherPreviewResponse>error(
                                    new ShopException("Product not found: " + line.productId()));
                        }
                        itemsTotal = itemsTotal.add(
                                product.getPrice().multiply(BigDecimal.valueOf(line.quantity())));
                    }
                    BigDecimal goods = itemsTotal;
                    List<org.granitesecurity.shop.dto.PackagingChoice> choices =
                            request.packaging() == null ? List.of() : request.packaging();
                    return packagingService.plan(products, lines, choices)
                            .flatMap(plan -> evaluate(request.code(), username, goods, plan.packagingTotal())
                                    .map(outcome -> toPreview(request.code(), outcome, goods,
                                            plan.packagingTotal())));
                });
    }

    private VoucherPreviewResponse toPreview(String rawCode, VoucherOutcome outcome,
                                             BigDecimal itemsTotal, BigDecimal packagingTotal) {
        BigDecimal discount = outcome.applied() ? outcome.discountTotal() : BigDecimal.ZERO.setScale(2);
        return new VoucherPreviewResponse(
                normalise(rawCode),
                outcome.applied(),
                outcome.applied() ? null : outcome.refusal().name(),
                outcome.applied() ? null : outcome.refusal().message(),
                outcome.applied() ? outcome.voucher().getPercentOff() : null,
                outcome.applied() ? outcome.voucher().getValidUntil() : null,
                itemsTotal,
                discount,
                packagingTotal,
                itemsTotal.subtract(discount).add(packagingTotal),
                shopCurrency);
    }

    /**
     * Upper-case and trimmed (V12): {@code spring25}, {@code SPRING25 } and
     * {@code Spring25} are one voucher, and the unique index makes sure two that
     * differ only by case cannot exist.
     */
    public static String normalise(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    public static boolean isBlank(String code) {
        return code == null || code.trim().isEmpty();
    }

    /**
     * What the code is worth on this cart, or why it is worth nothing.
     *
     * @param itemsTotal     the goods subtotal — the discount base (V7). Packaging is
     *                       charged in full and is passed only so the payable total can
     *                       be checked against the provider minimum.
     * @param packagingTotal what the chosen boxes add, undiscounted
     */
    public Mono<VoucherOutcome> evaluate(String rawCode, String username,
                                         BigDecimal itemsTotal, BigDecimal packagingTotal) {
        String code = normalise(rawCode);
        return voucherRepository.findByCode(code)
                .flatMap(voucher -> {
                    VoucherOutcome.Refusal window = checkWindow(voucher, Instant.now());
                    if (window != null) {
                        return Mono.just(VoucherOutcome.refused(window));
                    }
                    BigDecimal discount = discountOn(itemsTotal, voucher.getPercentOff());
                    BigDecimal payable = itemsTotal.subtract(discount).add(packagingTotal);
                    if (payable.compareTo(minimumPayableTotal) < 0) {
                        return Mono.just(VoucherOutcome.refused(VoucherOutcome.Refusal.BELOW_MINIMUM));
                    }
                    // Advisory only. The authoritative check is the primary key on
                    // voucher_redemption (V8) — this one exists so checkout can say
                    // "already used" before the shopper fills in an address, and it
                    // deliberately does not lock anything.
                    return redemptionRepository.existsByVoucherIdAndUsername(voucher.getId(), username)
                            .map(used -> used
                                    ? VoucherOutcome.refused(VoucherOutcome.Refusal.ALREADY_USED)
                                    : VoucherOutcome.applied(voucher, discount));
                })
                .defaultIfEmpty(VoucherOutcome.refused(VoucherOutcome.Refusal.NOT_FOUND));
    }

    /**
     * The whole of the expiry rule (V6), evaluated once, at placement.
     *
     * <p>Nothing re-checks it afterwards: an order placed a second before
     * {@code validUntil} keeps its discount however late it is paid, and a payment
     * retry never re-prices. A placed order's total does not move, which is what
     * payment, balance and delivery all already assume.
     */
    private VoucherOutcome.Refusal checkWindow(Voucher voucher, Instant now) {
        if (voucher.getRevokedAt() != null) {
            return VoucherOutcome.Refusal.REVOKED;
        }
        if (voucher.getValidFrom() != null && now.isBefore(voucher.getValidFrom())) {
            return VoucherOutcome.Refusal.NOT_YET_VALID;
        }
        if (!now.isBefore(voucher.getValidUntil())) {
            return VoucherOutcome.Refusal.EXPIRED;
        }
        return null;
    }

    /**
     * The items subtotal times the percentage, rounded HALF_UP to the currency's two
     * decimals — once, here, at placement (§6).
     *
     * <p>The rounded amount is then stored and every consumer subtracts <em>it</em>,
     * never recomputing from the percentage. That is what makes
     * {@code items + packaging - discount = total} an exact equality rather than
     * something that agrees to within a rappen.
     */
    public static BigDecimal discountOn(BigDecimal itemsTotal, Short percentOff) {
        return itemsTotal
                .multiply(BigDecimal.valueOf(percentOff))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Claims the voucher for this user, inside the caller's placement transaction.
     *
     * <p>The insert is the check (V8): a duplicate key is the only reliable answer to
     * two checkouts submitted at the same instant, and it is reported as a 409 rather
     * than a 400 because the request was valid when it was made — someone else's copy
     * of it simply arrived first.
     */
    public Mono<Void> redeem(Long voucherId, String username, Long orderId) {
        return redemptionRepository.insert(voucherId, username, orderId)
                .onErrorMap(DataIntegrityViolationException.class, e -> new ShopException(
                        VoucherOutcome.Refusal.ALREADY_USED.message(),
                        HttpStatus.CONFLICT, "Voucher already used"))
                .then();
    }
}
