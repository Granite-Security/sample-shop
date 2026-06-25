## CORS fixes (gateway + ui-shop)

- **`gateway/.../application.yaml`** — `globalcors` `allowed-origin-patterns` was a comma-separated scalar, not a YAML list; changed to proper list format under `allowed-origins`.
- **`gateway/.../GateSec.java`** — Removed the redundant `CorsConfigurationSource` bean (conflicted with YAML `globalcors`) and its `.cors()` call in the security chain.
- **`ui-shop/vite.config.ts`** — Added Vite dev proxy (`/api` → `localhost:8080`) so API calls stay same-origin during development.
- **`ui-shop/src/api.ts`** — Changed `BASE` from `http://localhost:8080` to `''` to use relative URLs through the proxy.
