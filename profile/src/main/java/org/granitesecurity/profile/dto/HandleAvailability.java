package org.granitesecurity.profile.dto;

/**
 * Answer to the authenticated availability check. {@code reason} is null when
 * available, otherwise the same message the {@code PUT} would have failed with, so the
 * form can show it before the user submits.
 */
public record HandleAvailability(String handle, boolean available, String reason) {}
