package org.granitesecurity.profile.dto;

public record PresignFileRequest(String fileName, String contentType, Long sizeBytes) {
}
