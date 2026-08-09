package org.granitesecurity.accounting.repository;

import org.granitesecurity.accounting.domain.JournalLine;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.UUID;

@Repository
public interface JournalLineRepository extends ReactiveCrudRepository<JournalLine, Long> {

    @Query("SELECT * FROM journal_line WHERE journal_id IN (:journalIds) ORDER BY id")
    Flux<JournalLine> findByJournalIds(Collection<UUID> journalIds);

    /**
     * The trial balance: every account's movement in one period, debits and credits kept
     * apart. Netting them into one signed number is what stops it being a trial balance —
     * the point is that the two columns are equal.
     *
     * <p>The period filter sits inside the join, not in a WHERE: filtering afterwards would
     * drop any account whose only lines are in other periods, so an account would go
     * missing from the trial balance rather than showing zero. A missing row and a zero row
     * mean different things, and only one of them is true.
     */
    @Query("""
            SELECT a.code                           AS account_code,
                   a.name                           AS account_name,
                   a.type                           AS account_type,
                   COALESCE(SUM(l.debit_minor), 0)  AS debit_minor,
                   COALESCE(SUM(l.credit_minor), 0) AS credit_minor
              FROM account a
              LEFT JOIN (
                    SELECT jl.account_code, jl.debit_minor, jl.credit_minor
                      FROM journal_line jl
                      JOIN journal j ON j.id = jl.journal_id
                     WHERE j.period_code = :periodCode
              ) l ON l.account_code = a.code
             GROUP BY a.code, a.name, a.type
             ORDER BY a.code
            """)
    Flux<TrialBalanceRow> trialBalance(String periodCode);

    record TrialBalanceRow(String accountCode, String accountName, String accountType,
                           long debitMinor, long creditMinor) {}
}
