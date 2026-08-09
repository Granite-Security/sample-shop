package org.granitesecurity.accounting.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One account in the chart (docs/finance/accounting.md §4.3).
 *
 * <p>The chart is seeded by Liquibase and there is no editor for it: a new account is
 * an accounting-policy decision and belongs in a reviewed migration. That is also why
 * the primary key is the code itself — {@code 4100} means one thing forever, and a
 * surrogate id would let two rows claim it.
 */
@Table("account")
@Getter
@Setter
public class ChartAccount {

    @Id
    private String code;

    private String name;

    /** ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE. */
    private String type;

    /** {@code DR} or {@code CR} — the side that increases this account. */
    @Column("normal_side")
    private String normalSide;

    /**
     * Sits against another account with the opposite normal side: {@code 4100}
     * contra-revenue is a debit against {@code 4000}. Flagged so a report can show it
     * as a deduction without hard-coding a list of codes.
     */
    private boolean contra;

    @Column("created_at")
    private Instant createdAt;
}
