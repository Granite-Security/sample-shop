package org.granitesecurity.profile.consumer;

import org.granitesecurity.profile.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Provisions a user profile when someone registers.
 *
 * <p>Before this existed, a profile row was created lazily on first visit to
 * "My Profile" containing only the username — email and name were always null,
 * because nothing ever told this service who the user was. auth-server collects
 * them at registration and writes them to authdb; this closes the gap.
 *
 * <p>Second consumer on {@code identity.events}, alongside notification's. Adding it
 * required no producer change beyond carrying the names on the event.
 */
@Component
public class UserRegisteredConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProfileService profileService;

    public UserRegisteredConsumer(ProfileService profileService) {
        this.profileService = profileService;
    }

    @KafkaListener(topics = "identity.events", groupId = "profile.identity.events.consumer")
    public void consume(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = MAPPER.readValue(message, Map.class);
            if (!"UserRegistered".equals(string(data.get("type")))) {
                return;
            }
            String username = string(data.get("username"));
            if (username == null || username.isBlank()) {
                log.warn("UserRegistered without a username: {}", message);
                return;
            }

            // No dedupe table needed: provisioning is an upsert keyed by username and
            // only ever fills fields that are still null, so redelivery is a no-op and
            // it can never clobber something the user has since edited.
            profileService.provisionFromRegistration(
                            username,
                            string(data.get("email")),
                            string(data.get("firstName")),
                            string(data.get("lastName")))
                    .subscribe(
                            profile -> log.info("Provisioned profile for {}", username),
                            err -> log.error("Failed to provision profile for {}", username, err));
        } catch (Exception e) {
            log.error("Failed to process identity event: {}", message, e);
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
