// Dev fallback for `npm run dev` (Vite serves public/ as-is).
// In the Docker image this file is overwritten at container start from
// config.template.js via envsubst (see docker-entrypoint.sh).
window.__ENV__ = {
  OIDC_AUTHORITY: "http://localhost:8080/auth",
  OIDC_CLIENT_ID: "spa-client",
  STRIPE_PUBLISHABLE_KEY: ""
};
