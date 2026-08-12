package org.granitesecurity.shop.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A percentage discount code with an expiry date (docs/finance/vouchers.md).
 *
 * <p><strong>A voucher is not money.</strong> It is never issued, held or
 * transferred, has no CHF amount until an order exists to apply it to, and
 * evaporates on its expiry date having cost nothing. That is the whole reason it
 * lives here in {@code shop} and never reaches {@code balance} (V1) — see §2.2:
 * gifted credit is CHF that exists and is counted in the money supply, and pushing
 * a voucher through the same door would mint francs at redemption only to destroy
 * them.
 *
 * <p>The row is authoritative only at the moment of placement. What an order was
 * actually charged is snapshotted onto {@code customer_order} (V5), so editing or
 * revoking this cannot change a sale that already happened.
 */
@Data
@Table("voucher")
public class Voucher {
    @Id
    private Long id;

    /** Upper-case and trimmed, always — {@code spring25} and {@code SPRING25} are one voucher (V12). */
    private String code;

    /** Whole percent off the items subtotal, 1..100 (V7). Currency-free, which is why percentages came first. */
    @Column("percent_off")
    private Short percentOff;

    @Column("valid_from")
    private Instant validFrom;

    /**
     * Mandatory (V10), and evaluated at placement only (V6).
     *
     * <p>An order placed a second before this passes keeps its discount however late
     * it is paid, and a payment retry never re-prices — a placed order's total does
     * not move, which is what every downstream service already assumes.
     */
    @Column("valid_until")
    private Instant validUntil;

    /** Set instead of deleting (V13). The one lever that works instantly if a code leaks. */
    @Column("revoked_at")
    private Instant revokedAt;

    /** What this campaign was, for the admin list. Never shown to shoppers. */
    private String description;

    @Column("created_by")
    private String createdBy;

    @Column("created_at")
    private Instant createdAt;

    public Voucher() {
    }

    public Voucher(String code, Short percentOff, Instant validFrom, Instant validUntil,
                   String description, String createdBy) {
        this.code = code;
        this.percentOff = percentOff;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.description = description;
        this.createdBy = createdBy;
    }
}
