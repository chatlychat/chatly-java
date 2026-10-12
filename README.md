# Chatly Java SDK

The official Java SDK for the [Chatly](https://chatlychat.com) API — live chat, shared inbox,
tickets, knowledge base, and the AI layer on top of them.

**Server-side only.** It authenticates with a `ck_…` workspace API key, which is a bearer
credential for your entire workspace: every conversation, every contact, every message. Anyone who
has it can read and write all of it. If you need chat in a browser or a mobile app, use
`@chatlychat/sdk-js` or the Kotlin/Swift SDKs instead — those authenticate per visitor and hold no
workspace credential.

- **Java 17+**
- **Zero runtime dependencies.** `java.net.http`, `javax.crypto`, and a 550-line JSON codec we own.
  One jar, no transitive tree, no Jackson version to fight with yours.
- Automatic retries with backoff, idempotency keys on every write, typed errors, auto-pagination.

---

## Install

### Maven

```xml
<dependency>
  <groupId>com.chatlychat</groupId>
  <artifactId>chatly-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.chatlychat:chatly-java:0.1.0")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'com.chatlychat:chatly-java:0.1.0'
}
```

---

## Quickstart

```java
import com.chatlychat.chatly.Chatly;
import java.util.Map;

Chatly chatly = Chatly.builder().apiKey(System.getenv("CHATLY_API_KEY")).build();

System.out.println(chatly.whoami());                      // which workspace, which scopes

Map<String, Object> contact = chatly.contacts().upsert(Map.of(
    "externalId", "u-42", "email", "jamie@acme.io", "name", "Jamie"));

chatly.events().track((String) contact.get("id"), "checkout.completed", Map.of("orderId", "O-7"));

for (Map<String, Object> cv : chatly.conversations().listAuto()) {   // every page, lazily
    System.out.println(cv.get("id") + " " + cv.get("subject"));
}
```

Get your key from the dashboard: **Settings → API keys**. Keep it in an environment variable or a
secret manager, never in source.

`whoami()` is worth calling first in any new integration. It tells you the workspace and the scopes
the key actually has, which turns a mis-scoped key into an obvious error on line one rather than a
403 from an unrelated endpoint three weeks later.

### Construction options

```java
Chatly chatly = Chatly.builder()
    .apiKey(System.getenv("CHATLY_API_KEY"))
    .baseUrl("http://localhost:4010")             // another environment; default is api.chatlychat.com
    .timeout(Duration.ofSeconds(30))              // per attempt; default 15s
    .retryPolicy(RetryPolicy.defaults().withMaxRetries(4))
    .httpClient(myHttpClient)                     // your own proxy / SSLContext / executor
    .userAgent("acme-billing/2.1")                // appended after chatly-sdk-java/0.1.0
    .build();
```

The client is **immutable and thread-safe**. Build one per process and share it — it holds the
`HttpClient` whose connection pool you want reused. Every call blocks the calling thread; there is
no async API in 0.1.0, so use a virtual thread or an executor if you need concurrency.

An empty or blank API key is rejected by `build()`, not by the first call. An unset environment
variable is the usual cause and the constructor is the easiest place to see it.

---

## The full method surface

55 methods across 13 namespaces. These are the API routes that accept an API key; a route that
requires a signed-in person is not here, because a method that always returns 403 is worse than no
method at all.

Return types: `Map<String, Object>` for a JSON object, `List<Map<String, Object>>` for a JSON array,
`Page<Map<String, Object>>` for a cursor page. `*Auto` variants return a `Pager` (see
[Pagination](#pagination)).

### Root

| Method | HTTP |
| --- | --- |
| `chatly.whoami()` | `GET /v1/api-keys/whoami` |

### `chatly.contacts()`

| Method | HTTP |
| --- | --- |
| `list()` / `list(Query)` → `Page` | `GET /v1/contacts` |
| `listAuto()` / `listAuto(Query)` → `Pager` | `GET /v1/contacts` (walked) |
| `get(contactId)` | `GET /v1/contacts/{id}` |
| `upsert(Map)` | `POST /v1/contacts` |
| `bulkUpsert(List<Map>)` — 1 to 5000 rows | `POST /v1/contacts/bulk` |
| `merge(contactId, mergedId)` | `POST /v1/contacts/{id}/merge` |
| `addTags(contactId, tag)` — also `addTag(…)` | `POST /v1/contacts/{id}/tags` |
| `removeTag(contactId, tag)` | `DELETE /v1/contacts/{id}/tags/{tag}` |
| `unsubscribe(contactId, boolean)` | `POST /v1/contacts/{id}/unsubscribe` |
| `journey(contactId)` → `Page`, `journeyAuto(…)` | `GET /v1/contacts/{id}/journey` |

`upsert` matches on `externalId` (falling back to `email`) and updates the existing row when it
finds one. Every field is optional server-side, so `upsert(Map.of())` creates an anonymous contact
rather than erroring — if you expect a match, check `externalId` is really in the map. A typo in
that key creates a new contact on every call and the `201` response does not tell you so.

`addTags` is singular despite the name: the route takes one `tag` per request. The name matches the
shared cross-language surface list; `addTag` is the same call under a name that reads correctly.

### `chatly.conversations()`

| Method | HTTP |
| --- | --- |
| `list()` / `list(Query)` → `Page`, `listAuto(…)` | `GET /v1/conversations` |
| `get(conversationId)` | `GET /v1/conversations/{id}` |
| `messages(conversationId)` → `Page`, `messagesAuto(…)` | `GET /v1/conversations/{id}/messages` |
| `send(conversationId, String)` / `send(conversationId, Map)` | `POST /v1/conversations/{id}/messages` |
| `unattended()` → `Page` (plus `total()`) | `GET /v1/conversations/unattended` |
| `agentHandling(conversationId)` | `GET /v1/conversations/{id}/agent-handling` |
| `setAgentHandling(conversationId, state)` | `PUT /v1/conversations/{id}/agent-handling` |
| `generatedReplies(conversationId)` → `List` | `GET /v1/conversations/{id}/generated-replies` |
| `generateReply(conversationId, body)` | `POST /v1/conversations/{id}/generated-replies` |
| `scheduledMessages(conversationId)` → `List` | `GET /v1/conversations/{id}/scheduled-messages` |
| `scheduleMessage(conversationId, body, sendAt)` | `POST /v1/conversations/{id}/scheduled-messages` |
| `cancelScheduled(scheduledMessageId)` | `DELETE /v1/conversations/scheduled-messages/{id}` |

`send` posts a message immediately. `generateReply` creates a **draft** a human can approve, edit or
reject — use that one when your agent must not speak to a customer unreviewed.

`setAgentHandling` needs an API key **linked to a machine user** (`Settings → API keys → Link
machine user`). An unlinked key gets a 403 whatever its scopes, and so does a human session. It is
the only method here with a precondition beyond scopes.

`sendAt` is an ISO-8601 instant. `java.time.Instant.toString()` produces exactly the accepted form;
a local date-time with no offset is rejected rather than guessed at.

### `chatly.tickets()`

| Method | HTTP |
| --- | --- |
| `list()` / `list(Query)` → `Page`, `listAuto(…)` | `GET /v1/tickets` |
| `comments(ticketId)` → `List` | `GET /v1/tickets/{id}/comments` |
| `forConversation(conversationId)` → `List` | `GET /v1/tickets/for-conversation/{id}` |
| `queues()` → `List` | `GET /v1/tickets/queues` |
| `merge(ticketId, loserId)` | `POST /v1/tickets/{id}/merge` |
| `split(ticketId, title)` | `POST /v1/tickets/{id}/split` |
| `bulk(List<String> ids, action)` / `bulk(Map)` | `POST /v1/tickets/bulk` |

### `chatly.macros()`

| Method | HTTP |
| --- | --- |
| `list()` / `list(Query)` → `List` | `GET /v1/macros` |
| `create(Map)` | `POST /v1/macros` |
| `update(macroId, Map)` | `PATCH /v1/macros/{id}` |
| `delete(macroId)` → `void` | `DELETE /v1/macros/{id}` |

`create` requires `name`, `scope`, and either a `body` or a non-empty `actions` array; `teamId` is
required when `scope` is `"team"`. The server enforces that; the SDK does not duplicate the
conditional schema, because an SDK-side copy is how you end up rejecting a payload the API would
have accepted.

`update` is a partial update — send only the keys you want changed.

`list` returns a bare array, not a page: a macro picker has to autocomplete over the whole set, so
the route is pagination-exempt. There is no `listAuto` because there is nothing to walk.

### `chatly.kb()`

| Method | HTTP |
| --- | --- |
| `articles()` → `Page`, `articlesAuto(…)` | `GET /v1/kb/articles` |
| `article(slug)` | `GET /v1/kb/articles/{slug}` |
| `collections()` → `List` | `GET /v1/kb/collections` |

### `chatly.search()`

| Method | HTTP |
| --- | --- |
| `query(term)` / `query(term, Query)` → `Page`, `queryAuto(…)` | `GET /v1/search?q=…` |
| `kb(term)` → `Page` | `GET /v1/search?q=…&kinds[]=kb` |

The term is required and capped at 256 characters; both bounds are the server's and are checked
locally so an over-long term fails before the round trip. The page carries `facets` and `tier` on
`page.raw()` — `tier` tells you which backend answered, which is the first thing to look at when
results surprise you.

### `chatly.events()`

| Method | HTTP |
| --- | --- |
| `track(contactId, name)` / `track(contactId, name, props)` | `POST /v1/cdp/events` |
| `list()` / `list(Query)` → `Page`, `listAuto(…)` | `GET /v1/cdp/events` |

Scope the read to one person with `Query.of().add("contactId", id)`.

### `chatly.webhooks()`

| Method | HTTP |
| --- | --- |
| `deliveries()` → `Page`, `deliveriesAuto(…)` | `GET /v1/webhooks/deliveries` |
| `delivery(deliveryId)` | `GET /v1/webhooks/deliveries/{id}` |
| `redeliver(deliveryId)` | `POST /v1/webhooks/deliveries/{id}/redeliver` |

For *verifying* an incoming delivery, see [Webhook verification](#webhook-verification) — that is a
static helper and needs no client.

### `chatly.reports()`

Every method takes an optional `Query` for the date range. None is paginated.

| Method | HTTP | Shape |
| --- | --- | --- |
| `overview()` | `GET /v1/reports/overview` | object |
| `agents()` | `GET /v1/reports/agents` | **array**, one row per agent |
| `conversationsByDay()` | `GET /v1/reports/conversations-by-day` | **array** of `{day, count}` |
| `sla()` | `GET /v1/reports/sla` | object |

### `chatly.channels()`

| Method | HTTP |
| --- | --- |
| `list()` → `List` | `GET /v1/channels` |
| `get(channelId)` | `GET /v1/channels/{id}` |

Read-only. Creating or configuring a channel needs `channels:write`, which no key-accessible route
grants, so there is no `create` or `update` here.

Worth reading `originsRestricted` on a web channel: a channel with no origin allow-list accepts an
embed from **any** site, and an empty list looks identical to an absent one in raw JSON, so the API
sends that boolean to make the difference visible.

### `chatly.billing()`

| Method | HTTP |
| --- | --- |
| `plans()` → `List` | `GET /v1/billing/plans` |
| `usage(eventType)` / `usage(eventType, Query)` | `GET /v1/billing/usage?eventType=…` |

`eventType` is required and must be a known meter — an unrecognised value is a 400, not an empty
result. The list is in the dashboard under **Settings → Billing → Usage** and is deliberately not
hardcoded here; an SDK-side enum goes stale the first time a meter is added and then rejects a value
the API accepts.

### `chatly.workspace()`

| Method | HTTP |
| --- | --- |
| `settings()` | `GET /v1/workspace/settings` |
| `labels()` → `List` | `GET /v1/labels` |
| `inboxes()` → `List` | `GET /v1/inboxes` |
| `teamMembers(teamId)` → `List` | `GET /v1/teams/{id}/members` |
| `agentSkills()` | `GET /v1/agents/skills` |

`agentSkills()` returns an **object**, not an array — `{"skills": ["billing", "german"]}` — the
distinct skill labels in use across the workspace, which is what skill-based routing matches against.

**There is no method listing every team, or every agent, and that is deliberate.** `GET /v1/teams`
carries no `@AllowApiKey()`, so it requires a signed-in person and answers every API key with 403
whatever its scopes; `GET /v1/agents` does not exist for *any* caller, including an admin session —
the agents controller declares only `/v1/agents/skills` and `/v1/agents/{userId}/skills`. Both were
on an earlier draft of the shared surface list, and both would have been methods that always fail,
which is worse than no method at all. So you need a team id from elsewhere (the dashboard, or a
conversation's assignment) to call `teamMembers`.

For the skills of one *specific* agent, that route exists and accepts a key but is not on the typed
surface — reach it through the escape hatch:

```java
chatly.request("GET", "/v1/agents/" + Query.encodePathSegment(userId) + "/skills");
```

Looking for per-agent **metrics**? That is `chatly.reports().agents()`. The two are easy to confuse
because the paths differ by one prefix.

---

## Errors

Every failure is a `ChatlyException` or a subclass of one, and each carries `status()`, `code()`,
`requestId()`, `body()` and `retryable()`.

```java
import com.chatlychat.chatly.errors.*;

try {
    chatly.conversations().send(conversationId, reply);
} catch (RateLimitException e) {
    schedule(e.retryAfter().orElse(Duration.ofSeconds(30)));
} catch (AuthenticationException e) {
    log.error("the API key is wrong or revoked");          // mint a new key
} catch (PermissionException e) {
    log.error("the key lacks a scope: {}", chatly.whoami());  // widen the existing key
} catch (NotFoundException e) {
    log.warn("conversation {} is gone, or belongs to another workspace", conversationId);
} catch (ValidationException e) {
    log.error("payload rejected: {}", e.body().orElse(""));   // per-field detail is in the body
} catch (TransportException e) {
    log.error("never reached the API: {}", e.getMessage());   // the write MAY still have applied
} catch (ChatlyException e) {
    log.error("chatly {} {} (request {})", e.status(), e.code().orElse("-"),
              e.requestId().orElse("-"));
}
```

| Type | When |
| --- | --- |
| `AuthenticationException` | 401 — key missing, malformed, revoked |
| `PermissionException` | 403 — key valid, scope missing, or route needs a human |
| `NotFoundException` | 404 — no such row, **or** it is in another workspace |
| `ConflictException` | 409 — conflict, including an idempotency-key/body mismatch |
| `ValidationException` | 400 (and 422, which this API does not send) — payload failed the API's schema |
| `RateLimitException` | 429 — carries `retryAfter()` |
| `ServerException` | 5xx — already retried before it reached you |
| `TransportException` | DNS, connect, TLS, timeout, interrupt. `status()` is `0` |
| `ChatlyException` | any other non-2xx, and the base type to catch |

`requestId()` is the `x-request-id` response header. Quote it in a support ticket — it finds the
exact request in our logs. The API mints an id for every request and echoes the header on every
response, errors included, so it is normally present; when a response arrives without one — a
transport failure never got a response at all — the `Optional` is empty rather than a fabricated
value.

**`PermissionException` versus `AuthenticationException` is a distinction worth having.** One means
"mint a new key", the other means "add a scope to the key you have". You should never have to parse
a message string to tell them apart, and here you do not.

A `NotFoundException` on a row you are sure exists usually means the wrong workspace. Postgres
row-level security scopes every query to the key's workspace, so another tenant's row is
indistinguishable from one that was never created. It is a 404 by design, not a 403.

### Why the exceptions are unchecked

`ChatlyException extends RuntimeException`. The deciding argument is pagination: `Pager` must expose
an `Iterator` and a `Stream`, both of which fetch pages lazily from inside `Iterator.next()`, whose
JDK signature declares no checked exceptions. A checked `ChatlyException` could therefore never
escape a paginated walk — it would have to be wrapped in some unchecked carrier on the way out,
leaving you with two error types for one failure and a `catch` that silently misses half the cases.
The same applies to any lambda in `map`, `forEach` or `thenApply`.

The cost is real: the compiler will not remind you that `contacts().list()` can fail. The typed
subclasses are the compensation.

---

## Retries

Three attempts by default, exponential backoff with **full jitter**, `Retry-After` honoured.

Retried: connection failures, `429`, `5xx`. Not retried: every other `4xx`. A `400`, `404` or `422`
is a deterministic refusal — retrying it burns your latency budget three times to get the same
answer.

**A retried write reuses the `Idempotency-Key` of the attempt that failed.** That is the whole
reason the header exists, and it is why an SDK-level retry cannot double-post a message.

```java
Chatly patient = Chatly.builder()
    .apiKey(key)
    .retryPolicy(RetryPolicy.defaults()
        .withMaxRetries(5)
        .withMaxBackoff(Duration.ofSeconds(30)))
    .build();

Chatly impatient = Chatly.builder().apiKey(key).retryPolicy(RetryPolicy.none()).build();
```

A `Retry-After` longer than 60 seconds is **not** slept on — a library may not block your thread for
an hour on the server's say-so. The `RateLimitException` is thrown instead, with the server's full
advice intact on `retryAfter()` for you to schedule around. Raise the ceiling with
`withMaxRetryAfter(…)` if that is what you want.

---

## Idempotency

Every write (`POST`, `PATCH`, `PUT`, `DELETE`) carries an `Idempotency-Key`. By default it is a
fresh UUID per call.

Pin one when **you** are the retrier and the two attempts are the same logical operation:

```java
String key = UUID.randomUUID().toString();
Chatly pinned = chatly.withIdempotencyKey(key);

for (int attempt = 0; attempt < 3; attempt++) {
    try {
        return pinned.contacts().upsert(body);
    } catch (ChatlyException e) {
        if (!e.retryable()) throw e;
        Thread.sleep(backoffMillis(attempt));
    }
}
```

Same key + same body replays the first response. Same key + a **different** body is a `409`. The
server remembers a key for 24 hours. `withIdempotencyKey` returns a new client; the original is
unchanged and keeps minting fresh keys.

The key must be 16–128 characters — checked locally, because the server's refusal for a short one
reads like a missing header rather than a too-short one.

---

## Pagination

Never sort or page on `id`. Ids in this product are a mix of UUID v4 and v7, so `id` order is not
time order; the cursor is the only correct sequence.

```java
// one page, as returned
Page<Map<String, Object>> page = chatly.contacts().list(Query.of().limit(100));
page.items();
page.nextCursor();     // Optional<String>, empty on the last page
page.hasNextPage();

// every page, walked lazily
for (Map<String, Object> contact : chatly.contacts().listAuto()) { … }

// as a Stream — limit() really stops the walk, it does not just trim the result
List<String> emails = chatly.contacts().listAuto().stream()
    .map(c -> (String) c.get("email"))
    .filter(Objects::nonNull)
    .limit(500)
    .toList();

// a page at a time, when you want the envelope and not just the rows
for (Page<Map<String, Object>> p : chatly.contacts().listAuto().pages()) {
    log.info("{} rows, hasMore={}", p.size(), p.hasMore().orElse(null));
}

// a single page's envelope extras
Page<Map<String, Object>> waiting = chatly.conversations().unattended();
log.warn("{} conversations unanswered in total", waiting.total().orElse(-1L));
```

Nothing is fetched until you start iterating. `Pager` is single-use per traversal: iterating twice
performs the requests twice, so snapshot with `.toList()` if you need more than one pass.

Filters survive onto page two — the `Query` you pass is copied and the cursor set per page, and your
own `Query` object is never mutated underneath you.

**Three page envelopes exist on this API, not one.** Most routes send `{items, nextCursor}`; tickets,
KB articles, CDP events and webhook deliveries add `hasMore`; search adds `facets` and `tier`;
`conversations().unattended()` adds `total`. `Page` guarantees `items()` and `nextCursor()`, offers
`hasMore()` and `total()` as `Optional` — **empty**, not `false` or `0`, where the route never sent
them — and keeps the whole envelope on `raw()`.

A server that repeats a cursor would loop forever. `Pager` notices, yields what it already has, and
throws a `ChatlyException` with code `pagination_cursor_repeated` rather than spinning. Stopping
quietly would be a silently truncated result, which is worse.

---

## Webhook verification

Every delivery carries `x-livechat-signature: t=<unix seconds>,v1=<hex>`, where `v1` is
`HMAC-SHA256(secret, "<t>." + rawBody)`. The timestamp is bound **into** the digest — that is what
makes the replay window enforceable.

### Spring

```java
@PostMapping(path = "/webhooks/chatly", consumes = MediaType.ALL_VALUE)
public ResponseEntity<Void> onEvent(
        @RequestBody byte[] rawBody,
        @RequestHeader(Webhooks.SIGNATURE_HEADER) String signature) {

    if (!Webhooks.verifySignature(rawBody, signature, System.getenv("CHATLY_WEBHOOK_SECRET"))) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    Map<String, Object> event = Json.parseObject(new String(rawBody, StandardCharsets.UTF_8));
    handle((String) event.get("type"), event);
    return ResponseEntity.ok().build();
}
```

### Servlet

```java
protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    byte[] rawBody = req.getInputStream().readAllBytes();
    String signature = req.getHeader(Webhooks.SIGNATURE_HEADER);

    if (!Webhooks.verifySignature(rawBody, signature, secret)) {
        res.setStatus(401);
        return;
    }
    // … and only now parse it
}
```

**Take the raw bytes.** `@RequestBody byte[]`, or `getInputStream().readAllBytes()` — never a bound
object. Binding the body to a `Map` or a POJO and re-serialising it to verify reorders keys and
changes whitespace, and the digest will not match, so the handler rejects **100% of genuine
deliveries** while the parsed object looks perfectly correct in a debugger. This is the single most
common way a webhook integration fails. Every overload here takes bytes or a string; none takes an
object, on purpose.

Note for Spring users: if another `@RequestBody` on the same endpoint has already consumed the
stream, you get an empty array. Read the bytes first, verify, then parse — in that order.

`verifySignature` **returns `false` and never throws**: not for a malformed header, a missing field,
a non-numeric `t`, a stale timestamp, a null secret or odd-length hex. A verifier that throws is an
*accepted* webhook in a handler that forgot a `try/catch` — the exception becomes a 500, our
delivery system retries it, and nobody learns that unsigned traffic was reaching the endpoint.
Refusing by return value is the only shape that fails closed.

Tolerance is 300 seconds either side of now. Pass `null` to disable it only when re-verifying an
archived delivery; in a live handler that turns replay protection off entirely.

```java
// a fixed clock, for testing your own handler against a recorded delivery
Webhooks.verifySignature(body, header, secret, Duration.ofSeconds(300), 1700000000L);

// build a header, to drive your handler's own tests
String header = Webhooks.signatureHeaderFor(body, secret, Instant.now().getEpochSecond());
```

---

## Identity verification

When a workspace turns identity verification on, the widget must prove the `externalId` it claims
was issued by your backend rather than typed into a browser console. Without it, anyone who reads
your page source can open a chat as any of your users and read that person's history.

```java
String userHash = Identity.hash(System.getenv("CHATLY_IDV_SECRET"), user.getId());
model.addAttribute("chatlyUserHash", userHash);
```

```html
<script>
  Chatly('identify', {
    externalId: "[[${user.id}]]",
    userHash:   "[[${chatlyUserHash}]]",
    email:      "[[${user.email}]]"
  });
</script>
```

Lowercase hex HMAC-SHA256 of `externalId` under the workspace's `sek_…` secret, from **Settings →
Widget → Identity verification**.

**The secret must never reach a browser** — only the digest does. A `sek_…` in client-side
JavaScript, a mobile bundle, or a public build-time variable hands every visitor the ability to mint
a hash for any user id, which defeats the whole mechanism, and a secret in a bundle is not revocable
without a redeploy. In an SPA, compute the hash server-side and send it down with the session.

An empty secret or an empty id **throws**. Hashing under an empty key produces a well-formed digest
anyone can reproduce, so accepting one would switch identity verification off while leaving every
outward sign that it is on — and an unset environment variable is exactly how that happens.

---

## The escape hatch

140 API routes accept an API key; 55 have a typed method above. For the other 85:

```java
// any verb, any path, through the same auth, idempotency, retry and error handling
Object raw   = chatly.request("GET", "/v1/some/route", Query.of().add("limit", 10), null);
Map<String, Object> obj = chatly.requestObject("POST", "/v1/some/route", null, Map.of("a", 1));
List<Map<String, Object>> arr = chatly.requestArray("GET", "/v1/some/list", null, null);

// a paginated route with no typed method
for (Map<String, Object> row : chatly.requestPaginated("/v1/some/list", Query.of().limit(50))) { … }
```

Anything other than `GET` is treated as a write and carries an `Idempotency-Key`. The path must be
absolute and already percent-encoded where you interpolated an id or a name — `request` cannot tell
a path separator from a slash inside a tag name. `Query.encodePathSegment(…)` is available for that.

No response-shape checking happens in `request`, because nothing knows what shape to expect: you get
whatever the JSON decoded to.

---

## Why maps and not POJOs

Methods return `Map<String, Object>`, not generated response types. Two reasons.

First, zero dependencies means no reflective binder — and hand-written response classes for 55
routes would be a second schema to keep in sync with the API, which drifts the first time a field is
added and then silently drops it.

Second, a `Map` cannot be wrong about a field it has never heard of. A new response key is available
to you the day it ships, with no SDK upgrade.

The cost is casts and no compile-time field names. If you want typed models, bind the maps with your
own Jackson or Gson setup in your application — where you already have those libraries and control
their versions:

```java
Contact contact = objectMapper.convertValue(chatly.contacts().get(id), Contact.class);
```

One footgun worth knowing: the codec serialises a map in **its** iteration order, and `Map.of`'s
order is deliberately randomised per JVM run. That is semantically irrelevant to the server, which
parses JSON objects order-insensitively, but it matters if you ever assert on a request body in your
own tests. Use a `LinkedHashMap` there.

---

## Tests

145 JUnit 5 assertions across eight classes. Nothing in the suite touches the public network, needs
an API key, or needs a running API: HTTP is driven either by an injected `Transport.Sender` or by a
loopback `com.sun.net.httpserver.HttpServer` on an ephemeral port.

```
mvn test
```

Both transports are used on purpose, because they catch different things: an injected sender
validates the request the SDK *intended*; only real HTTP validates the request it actually produced.

### `surface.lock.json`

`mvn test` regenerates [`surface.lock.json`](./surface.lock.json) by **calling all 55 methods** and
recording the `METHOD /path` each one actually issued, with interpolated segments normalised back to
the manifest's placeholder form. It then asserts that set equals
`packages/sdk-spec/surface.json` — the manifest shared by every Chatly SDK.

The lock is committed, and regenerating it is part of the test run rather than a script someone
forgets. It exists because the cross-language parity gate used to match path strings in SDK *source
text*, which credited this SDK with 55/55 while two of those methods did not exist: a Javadoc
paragraph explaining that two routes were broken happened to contain their paths, and the gate read
documentation as implementation. Executing the method cannot be fooled that way, and it also catches
a path assembled through a helper, a one-character typo in a literal segment, and a method that
issues two requests where it should issue one.

### Without a network, and without JUnit

Maven downloads JUnit from Maven Central the first time it runs. On a machine with a cold `~/.m2`
and no network that is not a slow build, it is no build — so the same contract assertions are also
available with nothing but a JDK:

```
javac -d target/verify -cp target/classes \
    src/test/java/com/chatlychat/chatly/FakeSender.java \
    src/test/java/com/chatlychat/chatly/Verify.java
java -cp target/classes:target/verify com.chatlychat.chatly.Verify
```

49 assertions, exit code 0 or 1. It is a supplement, not a replacement — `mvn test` runs nearly
three times as many — and both read the same pinned vectors.

### Pinned cross-language vectors

Both of these are fixed across every Chatly SDK, so a digest computed here must match the one
computed by the Node, Python, Go, Ruby, PHP and .NET clients:

```
Identity.hash("sek_test_secret", "user_42")
  = 5e26772dce97d5ba995796e621f83f2e97e6f8c3f9386dbf153a98ab24c10271

secret  whsec_test
body    {"id":"evt_1"}            (14 bytes, no trailing newline)
header  t=1700000000,v1=c89214b5b5da833daed6f0b8c5bb6bd58cea9022bd80ccc78230f3942d632925
```

The second must be **refused** when the digest is taken over the body alone
(`030fa3b2413d1993c551364bd53bb9b3edb5c0c34d55dba6ada6041245632811`) — that construction shipped
once and rejected every genuine delivery the platform sent.

---

## License

MIT — see [LICENSE](./LICENSE).

---

## About this repository

This is a **mirror**. `chatly-java` is built from a private monorepo where the rest
of the platform, its migrations and its contract checks live, so a pull request here
cannot be merged directly — but a patch is still the fastest way to get something
fixed and we will port it with attribution.

What you can do here: read the source, run the tests, open an issue, or open a PR
and we will treat the diff as the proposal.

Issues: https://github.com/chatlychat/chatly-java/issues · Docs: https://chatlychat.com/docs
