package org.granitesecurity.authserver.user;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wraps the default OIDC user loading with local-DB provisioning, so that the
 * authorities on the resulting principal (and therefore the JWT {@code roles}
 * claim auth-server later issues) come from the {@code users}/{@code authorities}
 * tables, exactly like a form-login user.
 */
@Service
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final FederatedUserProvisioningService provisioningService;

    public GoogleOidcUserService(FederatedUserProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = delegate.loadUser(userRequest);

        UserEntity user = provisioningService.provision(
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                Boolean.TRUE.equals(oidcUser.getEmailVerified()),
                oidcUser.getGivenName(),
                oidcUser.getFamilyName()
        );

        Set<GrantedAuthority> authorities = user.getAuthorities().stream()
                .map(authority -> (GrantedAuthority) new SimpleGrantedAuthority(authority.getAuthority()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
    }
}
