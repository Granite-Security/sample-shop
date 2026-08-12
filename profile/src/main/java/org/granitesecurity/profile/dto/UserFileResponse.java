package org.granitesecurity.profile.dto;

import java.time.Instant;

public record UserFileResponse(
        Long id,
        String fileName,
        String url,
        String contentType,
        Long sizeBytes,
        boolean shared,
        Instant createdAt
) {}
