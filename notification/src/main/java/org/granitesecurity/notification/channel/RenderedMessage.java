package org.granitesecurity.notification.channel;

/**
 * A message after template rendering, ready for a channel to deliver.
 *
 * <p>{@code html} is null for channels that have no rich form (SMS, WhatsApp);
 * {@code text} is always populated and is the only field a plain-text channel needs.
 */
public record RenderedMessage(
        Channel channel,
        String recipient,
        String subject,
        String html,
        String text) {
}
