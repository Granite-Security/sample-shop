package org.granitesecurity.shop.service;

import org.granitesecurity.shop.dto.PackagingQuoteResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * What packaging a cart needs, priced. The single thing {@link PackagingService}
 * returns, used both to answer a quote and to place an order — so the number the
 * shopper was shown and the number they are charged come from the same code.
 *
 * @param groups         one entry per packaging group present in the cart, in name order
 * @param packagingTotal sum of the chosen options' totals; zero in quote mode, where
 *                       nothing has been chosen yet
 */
public record PackagingPlan(List<GroupPlan> groups, BigDecimal packagingTotal) {

    public static PackagingPlan empty() {
        return new PackagingPlan(List.of(), BigDecimal.ZERO.setScale(2));
    }

    public boolean required() {
        return !groups.isEmpty();
    }

    /**
     * @param units  total quantity in the cart belonging to this group
     * @param chosen the shopper's option, or null in quote mode
     */
    public record GroupPlan(Long groupId, String code, String name, String description,
                            int units, List<OptionPlan> options, OptionPlan chosen) {}

    /**
     * @param packages  {@code ceil(units / capacity)} for this option specifically —
     *                  a bigger box means fewer of them
     * @param unitCost  what the box costs us; carried through to the order row and the
     *                  event so accounting can expense it (D44)
     * @param isDefault what "the shopper did nothing" means, decided by the server
     */
    public record OptionPlan(Long optionId, String code, String name, String description,
                             String imageUrl, int capacity, int packages,
                             BigDecimal unitPrice, BigDecimal unitCost, BigDecimal total,
                             boolean isDefault) {}

    public PackagingQuoteResponse toQuote(String currency) {
        List<PackagingQuoteResponse.GroupQuote> quoted = groups.stream()
                .map(g -> new PackagingQuoteResponse.GroupQuote(
                        g.groupId(), g.code(), g.name(), g.description(), g.units(),
                        g.options().stream()
                                .map(o -> new PackagingQuoteResponse.OptionQuote(
                                        o.optionId(), o.code(), o.name(), o.description(),
                                        o.imageUrl(), o.capacity(), o.packages(),
                                        o.unitPrice(), o.total(), o.isDefault()))
                                .toList()))
                .toList();
        return new PackagingQuoteResponse(required(), currency, quoted);
    }
}
