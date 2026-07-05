package org.granitesecurity.profile.dto;

public record AddressRequest(
        String label,
        String recipientName,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String zipCode,
        String country,
        Boolean isDefault
) {}
