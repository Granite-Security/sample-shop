import { UserManager } from 'oidc-client-ts';

const origin = window.location.origin;

export const userManager = new UserManager({
  authority: 'http://localhost:8080/auth',
  client_id: 'spa-client',
  redirect_uri: origin + '/callback',
  scope: 'openid profile email',
  response_type: 'code',
  automaticSilentRenew: true,
  metadata: {
    issuer: 'http://localhost:8080/auth',
    authorization_endpoint: origin + '/auth/oauth2/authorize',
    token_endpoint: origin + '/auth/oauth2/token',
    jwks_uri: origin + '/auth/oauth2/jwks',
    userinfo_endpoint: origin + '/auth/userinfo',
    end_session_endpoint: origin + '/auth/connect/logout',
    token_endpoint_auth_methods_supported: ['none'],
  } as any,
});
