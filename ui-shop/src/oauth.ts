import { UserManager } from 'oidc-client-ts';

const origin = window.location.origin;
const authority = window.__ENV__?.OIDC_AUTHORITY ?? 'http://localhost:8080/auth';
const clientId = window.__ENV__?.OIDC_CLIENT_ID ?? 'spa-client';

export const userManager = new UserManager({
  authority,
  client_id: clientId,
  redirect_uri: origin + '/callback',
  scope: 'openid profile email',
  response_type: 'code',
  automaticSilentRenew: true,
  metadata: {
    issuer: authority,
    authorization_endpoint: origin + '/auth/oauth2/authorize',
    token_endpoint: origin + '/auth/oauth2/token',
    jwks_uri: origin + '/auth/oauth2/jwks',
    userinfo_endpoint: origin + '/auth/userinfo',
    end_session_endpoint: origin + '/auth/connect/logout',
    token_endpoint_auth_methods_supported: ['none'],
  } as any,
});
