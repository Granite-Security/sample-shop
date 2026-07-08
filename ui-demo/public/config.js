// Dev fallback for `npm run dev` (Vite serves public/ as-is).
// In a container this file can be overwritten at start from a template,
// exactly like ui-shop's docker-entrypoint.sh does.
window.__ENV__ = {
  OIDC_AUTHORITY: "http://localhost:8080/auth",
  OIDC_CLIENT_ID: "spa-client",
  STRIPE_PUBLISHABLE_KEY: "pk_test_51RsLu2AVptonqAQOut5sBxOroJPgKDOyhgOjpaQ1GYHJktScZxTzzI5u74gGSVSI9tmQElZFIR2LNcITRWKCDSef00aqo55i2K"
};
