package org.granitesecurity.accounting.service;

/**
 * The chart of accounts as constants (docs/finance/accounting.md §4.3). The codes are
 * seeded by migration; these exist so a posting rule reads as a sentence rather than
 * as four-digit literals.
 */
public final class Accounts {

    public static final String CASH = "1000";
    public static final String RECEIVABLES = "1100";
    public static final String ECL_ALLOWANCE = "1150";
    public static final String INVENTORY = "1200";
    public static final String EQUIPMENT = "1500";
    public static final String ACCUM_DEPRECIATION = "1550";

    /** Top-ups. Stored value we owe in goods or a refund — never revenue (§2.3). */
    public static final String STORED_VALUE = "2000";
    /** Paid but not delivered. We hold the money and still owe the goods (§2.1). */
    public static final String DEFERRED_REVENUE = "2010";
    public static final String REFUND_LIABILITY = "2100";
    public static final String ACCOUNTS_PAYABLE = "2500";
    public static final String DUE_TO_STAFF = "2600";

    public static final String OWNERS_CAPITAL = "3000";
    public static final String RETAINED_EARNINGS = "3900";

    /** Gross, recognised on delivery. */
    public static final String REVENUE = "4000";
    /** Gifted credit redeemed: consideration payable to a customer (§2.4). */
    public static final String CONTRA_GIFT = "4100";
    public static final String CONTRA_RETURNS = "4200";

    public static final String COGS = "5000";
    public static final String PROCESSOR_FEES = "6100";
    public static final String INVENTORY_ADJUSTMENTS = "6200";
    public static final String SHIPPING = "6300";
    public static final String DEPRECIATION = "6400";
    /** ECL expense. Never nets against revenue (D11). */
    public static final String IMPAIRMENT = "6500";
    public static final String OTHER_OPERATING = "6900";

    private Accounts() {}
}
