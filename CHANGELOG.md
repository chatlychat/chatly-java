# Changelog

All notable changes to the Chatly Java SDK. This project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`Webhooks.signChatlyV2(ChatlySignatureMintInput)` — mint an `x-chatly-signature-v2` value, for
  a test that stands up a fake Chatly.** Builder-built input (`base`, `secret`, `timestampSeconds`,
  `body`, `method`, `path`) rather than six positional arguments, because the construction is three
  strings in a row and a swapped `path`/`body` compiles and mints a digest nobody can reproduce.
  `base` has no default — `Base.TARGET` emits `b=t` over `<t>.<METHOD>.<path>.<rawBody>` and
  `Base.BODY` emits `b=b` over `<t>.<rawBody>`; an absent marker is never emitted.
  `Webhooks.chatlyV2BaseString(input)` returns the exact signed bytes, so a test can pin the
  construction and not only the digest. Both **throw** on an input they cannot sign faithfully (no
  base, an empty secret, a negative timestamp, a null body, a `TARGET` with no method or path),
  which is the opposite of the verifier and for the opposite reason. A port of `signChatlyV2` in
  `@chatlychat/sdk-node`, with both digests pinned in `ChatlySignatureMintTest` against values the
  reference produced — a mint asserted against its own base-string construction agrees with a wrong
  one. Not for signing real outbound traffic: nothing in this package sends a signed request.
- **`Webhooks.verifyChatlySignature(ChatlySignatureInput)` — one verifier for every surface Chatly
  signs.** Event webhooks, agent deliveries, AI tool calls, canvas steps, customer cards, workflow
  actions (v2 and legacy) and the unified `x-chatly-signature-v2` header, chosen by
  `ChatlySignatureSurface` rather than guessed from a header name that three surfaces share. Returns
  a `ChatlySignatureResult` record — `ok`, `surface`, a stable `reason` (eleven codes) and a
  printable `message` on refusal; `timestampMs` (normalised to milliseconds, `null` on the legacy
  workflow header), `base` and `secretIndex` on success. Accepts a secret list for rotation and
  reports the caller's own index; prefers the v2 header when `method` and `path` are given and never
  falls back from it; refuses a duplicated signature header instead of trial-verifying both. Never
  throws. A port of `verifyChatlySignature` in `@chatlychat/sdk-node`, pinned by the shared
  `signature_vectors.json` corpus. `Webhooks.verifySignature` is unchanged.
- **`bots().rotateExternalSecret(botId, Instant activateAt)` — a rotation with an overlap window.**
  Sends `{"activateAt": "<Instant.toString()>"}` (always UTC, `Z`). The new secret is returned
  now; the old one keeps signing every delivery until `activateAt`, so a verifier holding both
  never rejects one. The server caps it at 168 hours ahead (a 400, measured on its clock — not
  re-checked here); a past instant, or a `null` here, is the old hard cut. The response now
  carries `activatesAt` beside `signingSecret`: the switch instant, or `null` when the new secret
  is already signing. The one-argument form is unchanged on the wire: still a hard cut, still the
  body `{}`.
- **The whole key-accessible surface: 107 new methods, 162 in total.** Eleven new namespace
  accessors — `ai()`, `bots()`, `botRoutes()`, `brands()`, `canvasSteps()`, `customerCards()`,
  `customerCardEndpoints()`, `docsSources()`, `emailTemplates()`, `knowledgeGaps()`, `triggers()` —
  plus additions to `contacts()` (`cards`, `mood`), `conversations()` (`linked`, `unreadCounts`,
  `outcomes`, `recordOutcome`, `returnToBot`, `updateBlocks`), `tickets()` (`conversations`,
  `openCount`, `time`, `statusHistory`, `lineage`), `macros()` (`get`, `actions`, `templates`,
  `template`), `webhooks()` (`endpoints`, `create`, `events`, `schemaVersions`,
  `setSchemaVersion`), `reports()` (nine), `channels()` (`adapters`, `create`, `update`,
  `rotateSecret`), `billing()` (`summary`, `invoices`, `usageDaily`, `usageMonthly`) and
  `workspace()` (`keyScopes`, `secrets`).
- **Three more, added when routes that were already key-accessible reached the shared manifest on
  2026-10-14:** `chatly.me()` (`GET /v1/me`), `chatly.webhooks().delete(endpointId)`
  (`DELETE /v1/webhooks/{id}`) and `chatly.workspace().mine()` (`GET /v1/workspaces/me`).
  - `me()` is the liveness check: the only route on this surface with no `@RequirePermission` at
    all, so a key with no useful scope still gets a `200`. Its body is discriminated on
    `principal`, and for a `ck_` key it is the same object `whoami()` returns.
  - `webhooks().delete` closes the asymmetry that let a key add a delivery target and never remove
    one. The interim workaround — repointing the endpoint at a dead url — leaves the subscription
    live and the delivery log filling with failures, so undo it where it was followed.
  - `workspace().mine()` returns exactly one entry for a key, which makes it the cheapest way to
    confirm which tenant a key points at. Its `role` is the synthetic `restricted`, not a stored
    membership role.
- Three new auto-paginators, for the three `page` routes that came with it: `bots().listAuto()`,
  `bots().deliveriesAuto(botId)` and `triggers().listAuto()`. The manifest's `paged` count is 13
  now. `billing().invoices()` is deliberately **not** a fourteenth: it is cursor-paginated and
  spells its envelope `{data, nextCursor}`, so the generic walker — which reads `items` — would
  report an empty page forever rather than failing.
- `Resource.strings(…)` behind `channels().adapters()`, the one route on the surface whose bare
  array holds strings rather than objects (`z.array(z.string())`). The generic array decoder would
  have handed back a list of `{"value": "web"}` maps: reachable, wrong-looking, and discovered by
  printing it.

### Changed

- `ChannelsResource`'s documentation said channels were read-only from an API key, "because
  `channels:write` … no API-key-accessible route grants" it. That was **false**: `POST /v1/channels`,
  `PATCH /v1/channels/{id}` and `POST /v1/channels/{id}/secret/rotate` all carry `@AllowApiKey()`
  and all take `channels:write`. The three methods exist now and the claim is gone.
- `Chatly.request`'s javadoc no longer quotes "140 of 886 routes … the other 85" as current fact. It
  names the audited figures and points at `scripts/report-api-key-surface.mjs` for the live count,
  because the open-route count has moved twice since those numbers were written into prose.
- The surface fixture is four data classes (`SurfaceEntriesCore`, `…Automation`, `…Config`,
  `…Inbox`) behind the `SurfaceCalls` harness, with the shared sample ids in `SurfaceIds`. 162
  entries plus the harness is far past CONTRACT §9's 650-line ceiling; every file in this package is
  under it — the largest is 583 lines.
- Anti-vacuity floors raised with the surface: the manifest floor from 50 to 155, the declared shape
  distribution from 14/10/30/1 to **33/13/115/1**, and the paged-route count from 10 to 13. The
  paged count did not move for the last three routes — none of them pages.

### Notes on three routes that are here and look like they should not be

- `conversations().unreadCounts()` computes per-_user_ badges from `membership.userId`, which for an
  API key is the key's own id — a principal that has never opened a conversation, so every count is 0. It is implemented because it answers `200`: it returns a confidently wrong answer rather than
  failing, and a method carrying that warning is better than an integrator finding out alone.
- `conversations().recordOutcome()`, `outcomes()` and `updateBlocks()` need a second, **undeclared**
  scope. Past their own `conversations:write` / `messages:write`, the handler re-checks the thread
  against your read scope, and a key with neither `conversations:read` nor
  `conversations:read:assigned` is scoped to nobody's conversations — so every id answers 404 and
  the 404 does not say why. Documented on each method.
- `DELETE /v1/webhooks/{id}` was deliberately absent for half a day, while it was key-accessible and
  not yet on `packages/sdk-spec/surface.json` — a lock entry the manifest does not list fails the
  cross-SDK parity gate. It is on the manifest and is `webhooks().delete` now.

## 0.1.0 — 2026-10-12

First release. `com.chatlychat:chatly-java:0.1.0`, Java 17+, **zero runtime dependencies**.

### Added

- **`Chatly` client** with a builder: `Chatly.builder().apiKey(…).baseUrl(…).httpClient(…).build()`.
  Immutable and thread-safe; build one per process. An empty or blank API key is rejected by
  `build()`, not by the first call.
- **55 typed methods** across 13 namespace accessors — `contacts()`, `conversations()`, `tickets()`,
  `macros()`, `kb()`, `search()`, `events()`, `webhooks()`, `reports()`, `channels()`, `billing()`,
  `workspace()`, plus `whoami()`. These are exactly the API routes that accept a `ck_` workspace key.
- **Typed errors.** `ChatlyException` (unchecked) with `AuthenticationException`,
  `PermissionException`, `NotFoundException`, `ConflictException`, `ValidationException`,
  `RateLimitException` (with `retryAfter()`), `ServerException` and `TransportException`. Each
  exposes `status()`, `code()`, `requestId()`, `body()` and `retryable()`.
- **Retries**: three attempts by default, exponential backoff with full jitter, `Retry-After`
  honoured. Connection failures, `429` and `5xx` only — never another `4xx`. A retried write reuses
  the `Idempotency-Key` of the attempt that failed.
- **Idempotency**: a fresh UUID on every write; `withIdempotencyKey(key)` returns a derived client
  that pins one across calls, for retrying the same logical operation.
- **Auto-pagination**: `Pager` as an `Iterable`, a `Stream` view, and a page-at-a-time `pages()`
  view. Lazy — a `.limit(n)` on the stream stops the walk rather than trimming the result. Stops on
  a null cursor; refuses a repeated cursor instead of looping forever.
- **`Webhooks.verifySignature(…)`** — HMAC-SHA256 over `"<t>." + rawBody`, constant-time compare,
  300-second default tolerance, takes **raw bytes**. Returns `false` and never throws, for any
  malformed input. `Webhooks.signatureHeaderFor(…)` builds a header for testing your own handler.
- **`Identity.hash(secret, externalId)`** — the backend half of widget identity verification.
  Throws on an empty secret or id rather than returning a digest anyone could reproduce.
- **Escape hatch**: `request`, `requestObject`, `requestArray` and `requestPaginated` reach any of
  the 85 key-accessible routes with no typed method, through the same auth, idempotency, retry and
  error handling.
- **`Json`** — a hand-written 550-line JSON codec. Tested directly, including surrogate pairs,
  control-character escaping, rejection of trailing content, and a depth limit so a hostile body is
  an exception rather than a `StackOverflowError`.
- **`Query`** — an ordered, multi-valued query builder. Repeated keys stay repeated
  (`kinds[]=kb&kinds[]=article` is two parameters), and `Query.encodePathSegment` is public for use
  with the escape hatch.
- **155 tests** (JUnit 5), plus a dependency-free `Verify` main class with 49 assertions that runs on
  a JDK alone. Nothing in either needs the network, an API key, or a running API.
- **`surface.lock.json`**, regenerated by `mvn test` from the execution of all 55 methods and
  asserted equal to `packages/sdk-spec/surface.json`. Produced by calling the client, not by reading
  source text — the first version of the cross-language parity gate matched path strings in source
  and so credited this SDK with 55/55 while two of those methods did not exist, because a doc comment
  mentioning their paths read as an implementation.
- **A `shape` column on every lock entry** — `array` (14), `page` (10), `object` (30), `empty` (1) —
  declared by hand in `ResponseShape` from the handlers' `@ZodResponse` schemas and never read from
  the manifest, because a fixture derived from the manifest agrees with a wrong manifest. The fake
  answers each route with a body of its own declared shape, the suite checks the value handed back
  really has that shape, and a negative test requires all ten auto-paginators to throw on a bare
  array rather than read it as an empty page.

### Notes

- **The default base URL is `https://api.chatlychat.com`.** Four sibling SDKs once defaulted to
  `api.livechat.local` or `api.chatly.example`, neither of which resolves, so every call failed for
  anyone who followed the quickstart. A test asserts on the URL a default-built client actually
  requests, not just on the constant — asserting on the constant is what those SDKs already did.
- **No method lists every team or every agent, on purpose.** `GET /v1/teams` carries no
  `@AllowApiKey()` and answers every API key with 403; `GET /v1/agents` does not exist for any
  caller (the controller declares only `/v1/agents/skills` and `/v1/agents/{userId}/skills`). Both
  were on an earlier draft of the shared surface list and both would have been methods that always
  fail. `workspace().teamMembers(teamId)` and `workspace().agentSkills()` are the key-accessible
  routes that do exist. For per-agent metrics use `reports().agents()`.
- **`conversations().setAgentHandling(…)` needs an API key linked to a machine user.** An unlinked
  key gets a 403 whatever its scopes. It is the only method with a precondition beyond scopes.
- Errors are **unchecked** by design — `Iterator.next()` cannot throw a checked exception, so a
  checked `ChatlyException` could never escape a paginated walk. Rationale in the README.
- Methods return `Map<String, Object>` / `List<Map<String, Object>>` / `Page<…>`, not generated
  POJOs. Zero dependencies means no reflective binder, and a map cannot be wrong about a field it
  has never heard of. Bind to your own types in your application if you want them.
- There is **no async API** in this release. Every call blocks; use a virtual thread or an executor.
