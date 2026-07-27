package org.granitesecurity.profile.dto;

public record RegisterFileRequest(String key, String url, String fileName, String contentType, Long sizeBytes) {
}
