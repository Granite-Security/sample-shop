package org.granitesecurity.profile.dto;

/** Body of {@code PUT /api/profiles/me/handle}. Lowercased and validated service-side. */
public record HandleRequest(String handle) {}
