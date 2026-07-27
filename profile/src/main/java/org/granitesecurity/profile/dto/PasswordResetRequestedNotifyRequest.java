package org.granitesecurity.profile.dto;

public record PasswordResetRequestedNotifyRequest(String email, String resetLink) {
}
