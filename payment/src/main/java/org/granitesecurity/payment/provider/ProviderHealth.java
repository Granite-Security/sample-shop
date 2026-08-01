package org.granitesecurity.payment.provider;

import java.util.Map;

/**
 * @param up      whether the provider answered
 * @param details free-form, surfaced on /actuator/health/providers
 */
public record ProviderHealth(boolean up, Map<String, Object> details) {

    public static ProviderHealth up(Map<String, Object> details) {
        return new ProviderHealth(true, details);
    }

    public static ProviderHealth down(String message) {
        return new ProviderHealth(false, Map.of("error", message == null ? "unknown" : message));
    }
}
