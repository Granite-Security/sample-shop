/// <reference types="vite/client" />

interface Window {
  __ENV__?: {
    OIDC_AUTHORITY: string;
    OIDC_CLIENT_ID: string;
    STRIPE_PUBLISHABLE_KEY: string;
  };
}
