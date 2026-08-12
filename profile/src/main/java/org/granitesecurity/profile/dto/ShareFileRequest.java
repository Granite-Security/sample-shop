package org.granitesecurity.profile.dto;

/** Body of {@code PUT /api/profiles/me/files/{id}/share}. */
public record ShareFileRequest(Boolean shared) {}
