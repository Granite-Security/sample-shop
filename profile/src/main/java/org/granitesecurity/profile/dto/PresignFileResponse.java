package org.granitesecurity.profile.dto;

public record PresignFileResponse(String key, String uploadUrl, String publicUrl, long expiresIn) {
}
