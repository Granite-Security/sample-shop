package org.granitesecurity.storage.dto;

public record PresignResponse(String key, String uploadUrl, String publicUrl, long expiresIn) {
}
