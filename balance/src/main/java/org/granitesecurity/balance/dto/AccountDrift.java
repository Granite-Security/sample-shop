package org.granitesecurity.balance.dto;

/** An account whose cached balance disagrees with the sum of its ledger entries. */
public record AccountDrift(String username, long cachedMinor, long ledgerSumMinor) {

    public boolean drifted() {
        return cachedMinor != ledgerSumMinor;
    }
}
