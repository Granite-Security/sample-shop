package org.granitesecurity.notification.template;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import org.granitesecurity.notification.channel.Channel;
import org.granitesecurity.notification.channel.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches the message templates that live under
 * {@code resources/templates/<channel>/<event-type>.{subject,html,txt}.mustache}.
 *
 * <p>Producers never send rendered text — they publish domain facts, and all copy
 * lives here. See docs/notification/notification-microservice.md §3.
 */
@Component
public class TemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(TemplateRegistry.class);

    /**
     * HTML bodies are compiled with escaping ON, so {{ }} escapes by default — this is
     * what keeps a hostile reset token from becoming markup, and it replaces the
     * hand-rolled escapeHtml helper that profile's EmailTemplates used.
     */
    private static final Mustache.Compiler HTML = Mustache.compiler().escapeHTML(true);

    /**
     * Subject lines and plain-text bodies must NOT be escaped — running them through
     * HTML escaping would render a literal "&amp;" to the reader. This mirrors the old
     * templates, which escaped only in the HTML variants.
     */
    private static final Mustache.Compiler PLAIN = Mustache.compiler().escapeHTML(false);

    private final Map<String, Optional<TemplateSet>> cache = new ConcurrentHashMap<>();

    public Optional<RenderedMessage> render(String eventType,
                                            Channel channel,
                                            String recipient,
                                            Map<String, Object> model) {
        return lookup(eventType, channel).map(set -> new RenderedMessage(
                channel,
                recipient,
                set.subject().execute(model).strip(),
                set.html() == null ? null : set.html().execute(model),
                set.text().execute(model)));
    }

    private Optional<TemplateSet> lookup(String eventType, Channel channel) {
        return cache.computeIfAbsent(eventType + ":" + channel, key -> load(eventType, channel));
    }

    private Optional<TemplateSet> load(String eventType, Channel channel) {
        String dir = channel.name().toLowerCase(Locale.ROOT);
        String base = toKebabCase(eventType);
        try {
            Template subject = compile(PLAIN, dir, base, "subject");
            Template text = compile(PLAIN, dir, base, "txt");
            if (subject == null || text == null) {
                log.warn("No {} template for event type {} — nothing will be sent for it", channel, eventType);
                return Optional.empty();
            }
            // Optional: plain-text channels (SMS, WhatsApp) have no HTML variant.
            Template html = compile(HTML, dir, base, "html");
            return Optional.of(new TemplateSet(subject, html, text));
        } catch (IOException e) {
            log.error("Failed to load {} templates for event type {}", channel, eventType, e);
            return Optional.empty();
        }
    }

    private Template compile(Mustache.Compiler compiler, String dir, String base, String suffix) throws IOException {
        ClassPathResource resource = new ClassPathResource("templates/%s/%s.%s.mustache".formatted(dir, base, suffix));
        if (!resource.exists()) {
            return null;
        }
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return compiler.compile(reader);
        }
    }

    /** {@code PasswordResetRequested} -> {@code password-reset-requested}. */
    private static String toKebabCase(String pascalCase) {
        return pascalCase.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private record TemplateSet(Template subject, Template html, Template text) {
    }
}
