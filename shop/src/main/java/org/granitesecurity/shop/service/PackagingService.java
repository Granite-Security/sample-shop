package org.granitesecurity.shop.service;

import org.granitesecurity.shop.domain.Product;
import org.granitesecurity.shop.dto.PackagingChoice;
import org.granitesecurity.shop.dto.PackagingQuoteRequest;
import org.granitesecurity.shop.dto.PackagingQuoteResponse;
import org.granitesecurity.shop.dto.PlaceOrderRequest;
import org.granitesecurity.shop.repository.GroupOptionRow;
import org.granitesecurity.shop.repository.PackagingGroupOptionRepository;
import org.granitesecurity.shop.repository.PackagingOptionRepository;
import org.granitesecurity.shop.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prices the boxes a cart needs (docs/packaging/packaging.md §1).
 *
 * <p>One entry point, used by both the quote endpoint and checkout, so that the
 * figure a shopper is shown and the figure they are charged cannot drift apart.
 *
 * <p>The rule is {@code ceil(units / capacity)} per group, no bin-packing optimiser
 * (D40): thirteen truffles in twelve-capacity boxes is two boxes, and the second one
 * is mostly empty. An optimiser would need dimensions and weights we do not have.
 */
@Service
public class PackagingService {

    private final PackagingGroupOptionRepository groupOptionRepository;
    private final PackagingOptionRepository optionRepository;
    private final ProductRepository productRepository;

    /** Must match OrderService's — a quote in one currency and a charge in another is a bug. */
    @Value("${shop.currency:CHF}")
    private String shopCurrency;

    public PackagingService(PackagingGroupOptionRepository groupOptionRepository,
                            PackagingOptionRepository optionRepository,
                            ProductRepository productRepository) {
        this.groupOptionRepository = groupOptionRepository;
        this.optionRepository = optionRepository;
        this.productRepository = productRepository;
    }

    /**
     * Prices every box option for a cart that has not been ordered yet.
     *
     * <p>Nothing is stored and no stock is touched: this is the question asked between
     * the cart and the address, and the shopper may still walk away.
     */
    public Mono<PackagingQuoteResponse> quote(PackagingQuoteRequest request) {
        List<PlaceOrderRequest.LineItem> lines = request == null || request.items() == null
                ? List.of() : request.items();
        if (lines.isEmpty()) {
            return Mono.just(PackagingPlan.empty().toQuote(shopCurrency));
        }
        for (PlaceOrderRequest.LineItem line : lines) {
            if (line == null || line.productId() == null || line.quantity() <= 0) {
                return Mono.error(new ShopException(
                        "Each item must name a productId and a quantity greater than zero"));
            }
        }
        List<Long> productIds = lines.stream().map(PlaceOrderRequest.LineItem::productId).distinct().toList();
        return productRepository.findAllById(productIds)
                .collectMap(Product::getId, p -> p)
                // Unknown ids are silently ignored rather than a 400: a quote is not a
                // commitment, and placeOrder is where an unknown product must be refused.
                .flatMap(products -> plan(products, lines, null))
                .map(plan -> plan.toQuote(shopCurrency));
    }

    /**
     * @param products resolved products for the cart, keyed by id — the caller has
     *                 already loaded them to price the lines, so they are passed in
     *                 rather than fetched a second time
     * @param choices  the shopper's selections, or null to quote without choosing.
     *                 When non-null every group in the cart must be covered, and the
     *                 selections are validated against what the group actually allows.
     */
    public Mono<PackagingPlan> plan(Map<Long, Product> products, List<PlaceOrderRequest.LineItem> lines,
                                    List<PackagingChoice> choices) {
        Map<Long, Integer> unitsByGroup = new LinkedHashMap<>();
        for (PlaceOrderRequest.LineItem line : lines) {
            Product product = products.get(line.productId());
            // A product with no group needs no box: it already arrived in one.
            if (product == null || product.getPackagingGroupId() == null) {
                continue;
            }
            unitsByGroup.merge(product.getPackagingGroupId(), line.quantity(), Integer::sum);
        }

        if (unitsByGroup.isEmpty()) {
            // Choices sent for a cart that needs nothing are ignored rather than
            // rejected: the shopper removed the last truffle after the quote, which
            // is not an error they should have to understand.
            return Mono.just(PackagingPlan.empty());
        }

        return groupOptionRepository.findActiveByGroupIds(unitsByGroup.keySet())
                .collectList()
                .flatMap(rows -> build(unitsByGroup, rows, choices));
    }

    private Mono<PackagingPlan> build(Map<Long, Integer> unitsByGroup, List<GroupOptionRow> rows,
                                      List<PackagingChoice> choices) {
        Map<Long, List<GroupOptionRow>> byGroup = new LinkedHashMap<>();
        for (GroupOptionRow row : rows) {
            byGroup.computeIfAbsent(row.groupId(), k -> new ArrayList<>()).add(row);
        }

        for (Long groupId : unitsByGroup.keySet()) {
            if (!byGroup.containsKey(groupId)) {
                // A data fault, not a shopper fault: a product was put in a group whose
                // options were all retired. Better a loud 400 naming the group than an
                // order nobody can pack.
                return Mono.error(new ShopException(
                        "No packaging is available for group " + groupId
                                + "; the order cannot be packed. Please contact us."));
            }
        }

        Map<Long, Long> chosenByGroup;
        try {
            chosenByGroup = validateChoices(unitsByGroup.keySet(), choices);
        } catch (ShopException e) {
            return Mono.error(e);
        }

        List<PackagingPlan.GroupPlan> groups = new ArrayList<>();
        BigDecimal packagingTotal = BigDecimal.ZERO;

        List<Long> groupIds = new ArrayList<>(unitsByGroup.keySet());
        groupIds.sort(Comparator.comparing(id -> byGroup.get(id).get(0).groupName(),
                Comparator.nullsLast(String::compareToIgnoreCase)));

        for (Long groupId : groupIds) {
            List<GroupOptionRow> groupRows = byGroup.get(groupId);
            int units = unitsByGroup.get(groupId);
            GroupOptionRow first = groupRows.get(0);

            List<PackagingPlan.OptionPlan> options = new ArrayList<>(groupRows.size());
            for (int i = 0; i < groupRows.size(); i++) {
                // Rows arrive ordered by sort_order, so the first is the default:
                // what "the shopper expressed no preference" means is the server's
                // decision, not the UI's.
                options.add(toOptionPlan(groupRows.get(i), units, i == 0));
            }

            PackagingPlan.OptionPlan chosen = null;
            if (chosenByGroup != null) {
                Long optionId = chosenByGroup.get(groupId);
                if (optionId == null) {
                    return Mono.error(new ShopException(
                            "Choose packaging for " + first.groupName()));
                }
                chosen = options.stream()
                        .filter(o -> o.optionId().equals(optionId))
                        .findFirst()
                        .orElse(null);
                if (chosen == null) {
                    return explainRejectedOption(optionId, first.groupName());
                }
                packagingTotal = packagingTotal.add(chosen.total());
            }

            groups.add(new PackagingPlan.GroupPlan(groupId, first.groupCode(), first.groupName(),
                    first.groupDescription(), units, options, chosen));
        }

        return Mono.just(new PackagingPlan(groups, packagingTotal.setScale(2, RoundingMode.HALF_UP)));
    }

    /**
     * @return chosen option id per group, or null when quoting. Rejects anything the
     *         shopper could not have got from a quote of this cart.
     */
    private Map<Long, Long> validateChoices(Set<Long> cartGroupIds, List<PackagingChoice> choices) {
        if (choices == null) {
            return null;
        }
        Map<Long, Long> chosen = new LinkedHashMap<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (PackagingChoice choice : choices) {
            if (choice == null || choice.groupId() == null || choice.optionId() == null) {
                throw new ShopException("Each packaging choice must name a groupId and an optionId");
            }
            if (!cartGroupIds.contains(choice.groupId())) {
                // Almost always a stale quote: the cart changed under the UI. Rejecting
                // beats silently dropping it, because the total the shopper agreed to
                // was computed from a cart that no longer exists.
                throw new ShopException("Packaging chosen for group " + choice.groupId()
                        + ", which nothing in this order belongs to");
            }
            if (!seen.add(choice.groupId())) {
                throw new ShopException("Two packaging choices sent for group " + choice.groupId()
                        + "; one group gets one box type");
            }
            chosen.put(choice.groupId(), choice.optionId());
        }
        return chosen;
    }

    /**
     * The option was not among the group's active pairings. Says which of the three
     * reasons it was, because "invalid packaging option" leaves an admin with no idea
     * whether they retired the box, never allowed it, or the client sent a stale id.
     */
    private Mono<PackagingPlan> explainRejectedOption(Long optionId, String groupName) {
        return optionRepository.findById(optionId)
                .flatMap(option -> Mono.<PackagingPlan>error(new ShopException(
                        Boolean.TRUE.equals(option.getActive())
                                ? "'" + option.getName() + "' is not available for " + groupName
                                : "'" + option.getName() + "' is no longer offered")))
                .switchIfEmpty(Mono.error(new ShopException("Unknown packaging option: " + optionId)));
    }

    private PackagingPlan.OptionPlan toOptionPlan(GroupOptionRow row, int units, boolean isDefault) {
        int packages = packagesFor(units, row.capacity());
        BigDecimal unitPrice = row.price().setScale(2, RoundingMode.HALF_UP);
        return new PackagingPlan.OptionPlan(
                row.optionId(), row.optionCode(), row.optionName(), row.optionDescription(),
                row.imageUrl(), row.capacity(), packages, unitPrice,
                row.unitCost().setScale(2, RoundingMode.HALF_UP),
                unitPrice.multiply(BigDecimal.valueOf(packages)).setScale(2, RoundingMode.HALF_UP),
                isDefault);
    }

    /** Integer ceiling division: any remainder means one more box. */
    private static int packagesFor(int units, int capacity) {
        return (units + capacity - 1) / capacity;
    }
}
