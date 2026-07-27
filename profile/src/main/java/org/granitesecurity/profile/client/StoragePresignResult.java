package org.granitesecurity.profile.client;

public record StoragePresignResult(String key, String uploadUrl, String publicUrl, long expiresIn) {
}
