package org.granitesecurity.payment.web;

import org.junit.jupiter.api.Test;

/**
 * The allow-list (docs/bugs/redirects.md §4.4).
 *
 * <p>Worth testing rather than checking by hand: the failure mode is an open redirect,
 * and no amount of clicking through a working checkout will reveal it — you have to send
 * a forged origin on purpose.
 */
class StorefrontOriginsTest {

    private static final String FALLBACK = "https://granite-security.org";

    private StorefrontOrigins origins() {
        return new StorefrontOrigins("https://granite-security.org,https://sichocolate.com", FALLBACK);
    }

    @Test
    void keepsAnAllowListedOrigin() {
        assert "https://sichocolate.com".equals(origins().sanitise("https://sichocolate.com"))
                : origins().sanitise("https://sichocolate.com");
    }

    /** The bug this whole change exists to fix: the other domain must survive intact. */
    @Test
    void doesNotRewriteOneAllowListedDomainIntoTheOther() {
        assert !FALLBACK.equals(origins().sanitise("https://sichocolate.com"));
    }

    @Test
    void fallsBackForAnOriginNobodyConfigured() {
        assert FALLBACK.equals(origins().sanitise("https://evil.example")) : "open redirect";
    }

    /**
     * A near-miss is still a miss. Prefix matching would let
     * {@code granite-security.org.evil.example} through, which is the classic way an
     * allow-list gets bypassed.
     */
    @Test
    void fallsBackForADomainThatMerelyLooksLikeAnAllowedOne() {
        assert FALLBACK.equals(origins().sanitise("https://granite-security.org.evil.example"));
        assert FALLBACK.equals(origins().sanitise("https://notgranite-security.org"));
    }

    @Test
    void treatsATrailingSlashAndCasingAsTheSameOrigin() {
        assert "https://sichocolate.com".equals(origins().sanitise("https://SiChocolate.com/"));
    }

    @Test
    void fallsBackForNullAndBlank() {
        assert FALLBACK.equals(origins().sanitise(null));
        assert FALLBACK.equals(origins().sanitise("   "));
    }

    /**
     * A deployment that never sets the list still has to work — and must still redirect
     * somewhere, namely where it always did.
     */
    @Test
    void trustsTheConfiguredFallbackEvenWithAnEmptyAllowList() {
        StorefrontOrigins empty = new StorefrontOrigins("", FALLBACK);
        assert FALLBACK.equals(empty.sanitise(FALLBACK));
        assert FALLBACK.equals(empty.sanitise("https://sichocolate.com"));
    }
}
