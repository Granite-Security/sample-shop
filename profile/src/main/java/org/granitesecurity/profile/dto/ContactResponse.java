package org.granitesecurity.profile.dto;

/**
 * What the contact form gets back.
 *
 * <p>Deliberately not a {@link MessageResponse}: an anonymous visitor has no inbox, so
 * a message id, read state and counterparty avatar are all things they can do nothing
 * with — and a honeypot rejection has no row to describe in the first place (§11.1).
 */
public record ContactResponse(String status) {

    public static ContactResponse received() {
        return new ContactResponse("RECEIVED");
    }
}
