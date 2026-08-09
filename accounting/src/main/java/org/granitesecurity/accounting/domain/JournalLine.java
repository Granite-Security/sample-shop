package org.granitesecurity.accounting.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * One line of one entry. Exactly one side is non-zero — a line carrying both a debit
 * and a credit is two lines somebody did not write, and it makes every sum over this
 * table ambiguous. The CHECK constraint says so too.
 */
@Table("journal_line")
@Getter
@Setter
public class JournalLine {

    @Id
    private Long id;

    @Column("journal_id")
    private UUID journalId;

    @Column("account_code")
    private String accountCode;

    @Column("debit_minor")
    private long debitMinor;

    @Column("credit_minor")
    private long creditMinor;

    /** Who we owe, on a 2600 line (D35). */
    private String party;

    private String memo;
}
