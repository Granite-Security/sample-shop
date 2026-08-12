package org.granitesecurity.profile.dto;

/** Body of {@code PUT /api/profiles/me/visibility}. */
public record VisibilityRequest(Boolean publicProfile) {}
