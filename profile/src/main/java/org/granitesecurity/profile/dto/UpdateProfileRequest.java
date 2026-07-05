package org.granitesecurity.profile.dto;

public record UpdateProfileRequest(
        String email,
        String firstName,
        String lastName
) {}
