package org.granitesecurity.storage.dto;

public record PresignRequest(String fileName, String contentType, String scope) {
}
