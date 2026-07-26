package org.granitesecurity.profile.dto;

public record ProfileResponse(
        Long id,
        String username,
        String email,
        String firstName,
        String lastName,
        java.time.Instant createdAt,
        java.time.Instant updatedAt
) {}
