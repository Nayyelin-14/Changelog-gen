# AGENTS.md

## Layout

- `service/` — Quarkus 3 (Java 25) API. REST base path is `/api`.
- `web-view/` — React 19 + Vite frontend; proxies `/api` to the backend.

## Commands

Backend:

```bash
cd service
./mvnw test          # full suite
./mvnw quarkus:dev   # dev server
```

Frontend:

```bash
cd web-view
npm run lint         # oxlint
npm run build        # tsc -b && vite build
npm run test         # vitest
```

## Testing gotcha: stop the dev server before `./mvnw test`

The dev server binds port **8081** (`%dev.quarkus.http.port=8081` in
`service/src/main/resources/application.properties`). Quarkus tests bind the same port, so if
`./mvnw quarkus:dev` is still running, the test app fails to start with
`QuarkusBindException: Port already bound: 8081`.

**The suite does not fail loudly in that case** — the first `@QuarkusTest` aborts and every
remaining `@QuarkusTest` class is reported as skipped, so a green-looking run can hide dozens of
unexecuted tests. Always check the `Skipped:` count.

```bash
# stop the dev server first, then:
cd service && ./mvnw test
```

A healthy full run is ~286 tests, 0 failures, 3 skipped (the 3 skips are conditional model
catalog/latency/readiness tests that need a live provider). If the skipped count jumps into the
dozens, a `@QuarkusTest` app almost certainly failed to bind 8081.

## Notes

- Local dev reads `service/.env`; CI (`.github/workflows`) runs `./mvnw test` and the frontend
  lint/build checks on PRs into `dev`.
- `GITHUB_TOKEN` (→ `github.token`) is the server-side service-account PAT used for fetching
  commits, and is separate from the per-user GitHub OAuth login (`github.oauth.*`). An expired
  local token only breaks local data fetches — it is not the login feature.
