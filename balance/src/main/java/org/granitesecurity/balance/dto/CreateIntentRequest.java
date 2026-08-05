package org.granitesecurity.balance.dto;

/**
 * What payment's BalanceProvider sends to open a payment. Amounts arrive in
 * rappen because payment already has them that way (Money#minorUnits).
 */
public record CreateIntentRequest(String username, long amountMinor, Long orderId) {}
