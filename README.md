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

163 methods across 25 namespaces — the whole key-accessible surface. These are the API routes that
accept an API key; a route that requires a signed-in person is not here, because a method that
always returns 403 is worse than no method at all. Contact block/unblock is the clearest example of
what is deliberately missing: those routes record _who_ blocked a contact, and a key is not a
person.

Return types: `Map<String, Object>` for a JSON object, `List<Map<String, Object>>` for a JSON array,
`Page<Map<String, Object>>` for a cursor page. `*Auto` variants return a `Pager` (see
[Pagination](#pagination)).

### Root

| Method            | HTTP                      |
| ----------------- | ------------------------- |
| `chatly.whoami()` | `GET /v1/api-keys/whoami` |
| `chatly.me()`     | `GET /v1/me`              |

`me()` is **the liveness check**, and the only route on this surface with no `@RequirePermission`
at all. What a key learns is its own workspace id and its own grant list — the two things it
presented in order to ask — so a scope requirement could only withhold a credential's description
from the credential itself, which is how integrators end up discovering their scopes by provoking
403s. A key with no useful scope still gets a `200`, and that answer is true.

The body is discriminated on `principal`: `"api_key"` gives the same object as `whoami()`, because
there is no user behind a key. Use `me()` to prove a key works at all, and `whoami()` (which needs
`workspace:read`) when you are already inside a scoped flow. Both answer while the workspace is
suspended.

### `chatly.contacts()`

| Method                                                  | HTTP                                  |
| ------------------------------------------------------- | ------------------------------------- |
| `list()` / `list(Query)` → `Page`                       | `GET /v1/contacts`                    |
| `listAuto()` / `listAuto(Query)` → `Pager`              | `GET /v1/contacts` (walked)           |
| `get(contactId)`                                        | `GET /v1/contacts/{id}`               |
| `upsert(Map)`                                           | `POST /v1/contacts`                   |
| `bulkUpsert(List<Map>)` — 1 to 5000 rows                | `POST /v1/contacts/bulk`              |
| `merge(contactId, mergedId)`                            | `POST /v1/contacts/{id}/merge`        |
| `addTags(contactId, tag)` — also `addTag(…)`            | `POST /v1/contacts/{id}/tags`         |
| `removeTag(contactId, tag)`                             | `DELETE /v1/contacts/{id}/tags/{tag}` |
| `unsubscribe(contactId, boolean)`                       | `POST /v1/contacts/{id}/unsubscribe`  |
| `journey(contactId)` → `Page`, `journeyAuto(…)`         | `GET /v1/contacts/{id}/journey`       |
| `cards(contactId)` / `cards(contactId, conversationId)` | `POST /v1/contacts/{id}/cards`        |
| `mood(contactId)` / `mood(contactId, days)`             | `GET /v1/contacts/{id}/mood`          |

`upsert` matches on `externalId` (falling back to `email`) and updates the existing row when it
finds one. Every field is optional server-side, so `upsert(Map.of())` creates an anonymous contact
rather than erroring — if you expect a match, check `externalId` is really in the map. A typo in
that key creates a new contact on every call and the `201` response does not tell you so.

`addTags` is singular despite the name: the route takes one `tag` per request. The name matches the
shared cross-language surface list; `addTag` is the same call under a name that reads correctly.

`cards` is a POST that reads: it fans out to the urls configured with `chatly.customerCards()`,
signs each request, and returns what your own backend answered. Read each entry's `state` — a card
with no blocks may have timed out rather than having nothing to say.

`mood` returns an empty `buckets` array when there is **not enough signal**, which is not the same
as neutral.

### `chatly.conversations()`

| Method                                                       | HTTP                                                 |
| ------------------------------------------------------------ | ---------------------------------------------------- |
| `list()` / `list(Query)` → `Page`, `listAuto(…)`             | `GET /v1/conversations`                              |
| `get(conversationId)`                                        | `GET /v1/conversations/{id}`                         |
| `messages(conversationId)` → `Page`, `messagesAuto(…)`       | `GET /v1/conversations/{id}/messages`                |
| `send(conversationId, String)` / `send(conversationId, Map)` | `POST /v1/conversations/{id}/messages`               |
| `unattended()` → `Page` (plus `total()`)                     | `GET /v1/conversations/unattended`                   |
| `agentHandling(conversationId)`                              | `GET /v1/conversations/{id}/agent-handling`          |
| `setAgentHandling(conversationId, state)`                    | `PUT /v1/conversations/{id}/agent-handling`          |
| `generatedReplies(conversationId)` → `List`                  | `GET /v1/conversations/{id}/generated-replies`       |
| `generateReply(conversationId, body)`                        | `POST /v1/conversations/{id}/generated-replies`      |
| `scheduledMessages(conversationId)` → `List`                 | `GET /v1/conversations/{id}/scheduled-messages`      |
| `scheduleMessage(conversationId, body, sendAt)`              | `POST /v1/conversations/{id}/scheduled-messages`     |
| `cancelScheduled(scheduledMessageId)`                        | `DELETE /v1/conversations/scheduled-messages/{id}`   |
| `linked(conversationId)`                                     | `GET /v1/conversations/{id}/linked`                  |
| `unreadCounts()`                                             | `GET /v1/conversations/unread-counts`                |
| `outcomes(conversationId)`                                   | `GET /v1/conversations/{id}/outcomes`                |
| `recordOutcome(conversationId, String\|Map)`                 | `POST /v1/conversations/{id}/outcomes`               |
| `returnToBot(conversationId, botId)`                         | `POST /v1/conversations/{id}/return-to-bot`          |
| `updateBlocks(conversationId, messageId, List<Map>)`         | `PATCH /v1/conversations/{id}/messages/{mid}/blocks` |

`send` posts a message immediately. `generateReply` creates a **draft** a human can approve, edit or
reject — use that one when your agent must not speak to a customer unreviewed.

`setAgentHandling` needs an API key **linked to a machine user** (`Settings → API keys → Link
machine user`). An unlinked key gets a 403 whatever its scopes, and so does a human session. It is
the only method here with a precondition beyond scopes.

`sendAt` is an ISO-8601 instant. `java.time.Instant.toString()` produces exactly the accepted form;
a local date-time with no offset is rejected rather than guessed at.

`unreadCounts()` is **nearly useless from a key** and is here because it answers `200` rather than
failing: it computes per-_user_ badges from the caller's `membership.userId`, which for a key is the
key's own id — a principal that has never opened a conversation. For "what is waiting", use
`unattended()` and read `page.total()`.

`recordOutcome`, `outcomes` and `updateBlocks` each need a _second_, undeclared scope. Beyond their
own `conversations:write` / `messages:write`, the handler re-checks that the thread is in your read
scope, and a key with neither `conversations:read` nor `conversations:read:assigned` is scoped to
nobody's conversations — so every id answers 404 and nothing says why.

`recordOutcome` refuses `valueCents` on anything but `purchased`, rather than ignoring it. And
nothing in Chatly sums `valueCents`: there is no revenue report. It lands on the CDP event, where
the contact timeline shows it and a goal can convert on it.

`linked` carries an `items` array with **no cursor** — an object, not a page.

### `chatly.tickets()`

| Method                                           | HTTP                                    |
| ------------------------------------------------ | --------------------------------------- |
| `list()` / `list(Query)` → `Page`, `listAuto(…)` | `GET /v1/tickets`                       |
| `comments(ticketId)` → `List`                    | `GET /v1/tickets/{id}/comments`         |
| `forConversation(conversationId)` → `List`       | `GET /v1/tickets/for-conversation/{id}` |
| `queues()` → `List`                              | `GET /v1/tickets/queues`                |
| `merge(ticketId, loserId)`                       | `POST /v1/tickets/{id}/merge`           |
| `split(ticketId, title)`                         | `POST /v1/tickets/{id}/split`           |
| `bulk(List<String> ids, action)` / `bulk(Map)`   | `POST /v1/tickets/bulk`                 |
| `conversations(ticketId)` → `List`               | `GET /v1/tickets/{id}/conversations`    |
| `openCount(conversationId)`                      | `GET /v1/tickets/open-count/{cid}`      |
| `time(ticketId)`                                 | `GET /v1/tickets/{id}/time`             |
| `statusHistory(ticketId)`                        | `GET /v1/tickets/{id}/status-history`   |
| `lineage(ticketId)`                              | `GET /v1/tickets/{id}/lineage`          |

Every route here is gated on `conversations:read`, not on a tickets scope — there is no
`tickets:read` permission in this product, and a key scoped for tickets alone gets a 403 from all of
them.

`time` and `statusHistory` are objects with an `items` array and no cursor. `statusHistory` is
capped at 500 transitions and `truncated` is what distinguishes a capped timeline from a short one —
a cycle time computed from a truncated one measures the wrong interval.

### `chatly.macros()`

| Method                            | HTTP                              |
| --------------------------------- | --------------------------------- |
| `list()` / `list(Query)` → `List` | `GET /v1/macros`                  |
| `create(Map)`                     | `POST /v1/macros`                 |
| `update(macroId, Map)`            | `PATCH /v1/macros/{id}`           |
| `delete(macroId)` → `void`        | `DELETE /v1/macros/{id}`          |
| `get(macroId)`                    | `GET /v1/macros/{id}`             |
| `actions()`                       | `GET /v1/macros/actions`          |
| `templates()`                     | `GET /v1/macros/templates`        |
| `template(slug)`                  | `GET /v1/macros/templates/{slug}` |

`create` requires `name`, `scope`, and either a `body` or a non-empty `actions` array; `teamId` is
required when `scope` is `"team"`. The server enforces that; the SDK does not duplicate the
conditional schema, because an SDK-side copy is how you end up rejecting a payload the API would
have accepted.

`update` is a partial update — send only the keys you want changed.

`list` returns a bare array, not a page: a macro picker has to autocomplete over the whole set, so
the route is pagination-exempt. There is no `listAuto` because there is nothing to walk.

A **personal** macro belonging to a human agent is invisible to a key, in `list` and as a 404 from
`get`. The `macros` table has FORCE row-level security with a personal-scope clause: a
`scope = 'personal'` row is visible only when `app.current_user_id` matches its owner, and for a key
that is the key's own id. So a macro you can see in the dashboard can be absent here.

`templates()` is an object, `{items, total}`, where `total` is just `items.length` over a frozen
in-process catalog — not a hint at a second page. Installing a template is a `macros:write` route
that a key cannot reach; copy the body from `template(slug)` into your own `create(Map)`.

### `chatly.kb()`

| Method                                   | HTTP                         |
| ---------------------------------------- | ---------------------------- |
| `articles()` → `Page`, `articlesAuto(…)` | `GET /v1/kb/articles`        |
| `article(slug)`                          | `GET /v1/kb/articles/{slug}` |
| `collections()` → `List`                 | `GET /v1/kb/collections`     |

### `chatly.search()`

| Method                                                        | HTTP                          |
| ------------------------------------------------------------- | ----------------------------- |
| `query(term)` / `query(term, Query)` → `Page`, `queryAuto(…)` | `GET /v1/search?q=…`          |
| `kb(term)` → `Page`                                           | `GET /v1/search?q=…&kinds=kb` |

The term is required and capped at 256 characters; both bounds are the server's and are checked
locally so an over-long term fails before the round trip. The page carries `facets` and `tier` on
`page.raw()` — `tier` tells you which backend answered, which is the first thing to look at when
results surprise you.

Narrow with a repeated `kinds` parameter — two parameters, not one comma-joined value:

```java
chatly.search().query("refund", Query.of().add("kinds", "kb").add("kinds", "ticket"));
```

The valid kinds are exactly `contact`, `conversation`, `message`, `ticket` and `kb`. Anything else
is a `400`, because the server validates against an enum — so `article` is **not** a kind, however
much it reads like one. Omit the parameter to search all five.

Send plain `kinds`, not `kinds[]`. The server does accept the bracketed spelling, but only via a
compatibility shim added after that form was found to be silently _stripped_ — which made every
narrowed search run unnarrowed and return a complete, valid, wrong answer with a `200`. `kinds` is
the key the schema declares.

### `chatly.events()`

| Method                                                     | HTTP                  |
| ---------------------------------------------------------- | --------------------- |
| `track(contactId, name)` / `track(contactId, name, props)` | `POST /v1/cdp/events` |
| `list()` / `list(Query)` → `Page`, `listAuto(…)`           | `GET /v1/cdp/events`  |

Scope the read to one person with `Query.of().add("contactId", id)`.

### `chatly.webhooks()`

| Method                                       | HTTP                                          |
| -------------------------------------------- | --------------------------------------------- |
| `deliveries()` → `Page`, `deliveriesAuto(…)` | `GET /v1/webhooks/deliveries`                 |
| `delivery(deliveryId)`                       | `GET /v1/webhooks/deliveries/{id}`            |
| `redeliver(deliveryId)`                      | `POST /v1/webhooks/deliveries/{id}/redeliver` |

| `endpoints()` → `List` | `GET /v1/webhooks` |
| `create(Map)` | `POST /v1/webhooks` |
| `events()` | `GET /v1/webhooks/events` |
| `schemaVersions()` | `GET /v1/webhooks/schema-versions` |
| `setSchemaVersion(endpointId, version)` | `PATCH /v1/webhooks/{id}/schema-version` |
| `delete(endpointId)` | `DELETE /v1/webhooks/{id}` |

For _verifying_ an incoming delivery, see [Webhook verification](#webhook-verification) — that is a
static helper and needs no client.

`create(Map)` returns the **only** response that carries `secret`. There is no reveal route and no
rotation route: losing it means deleting the subscription and making another.

`setSchemaVersion` answers with `missingEventTypes` — the events this subscription asked for that
the pinned version does not define, by name. A non-empty list means those events stop being
delivered while the pin stands, so read it rather than treating the 200 as "nothing changed".

`delete` answers 200 with `{"ok": true}`, not a 204. It was missing `@AllowApiKey()` until
2026-10-14 — a key could add a subscription and never remove one, so a deploy pipeline registering
an endpoint per environment leaked one into the customer's workspace every run. **If you followed
the workaround of repointing the endpoint at a dead url, undo it**: that leaves the subscription
live, every event still attempts delivery, and the delivery log fills with failures. Deleting is the
only thing that stops the attempts.

### `chatly.reports()`

Every method takes an optional `Query` for the date range. None is paginated.

| Method                        | HTTP                                        | Shape                        |
| ----------------------------- | ------------------------------------------- | ---------------------------- |
| `overview()`                  | `GET /v1/reports/overview`                  | object                       |
| `agents()`                    | `GET /v1/reports/agents`                    | **array**, one row per agent |
| `conversationsByDay()`        | `GET /v1/reports/conversations-by-day`      | **array** of `{day, count}`  |
| `sla()`                       | `GET /v1/reports/sla`                       | object                       |
| `attribution()`               | `GET /v1/reports/attribution`               | **array**                    |
| `sources()`                   | `GET /v1/reports/sources`                   | **array**                    |
| `cohorts()`                   | `GET /v1/reports/cohorts`                   | object                       |
| `labels()`                    | `GET /v1/reports/labels`                    | object                       |
| `responseTimes()`             | `GET /v1/reports/response-times`            | object                       |
| `teams()`                     | `GET /v1/reports/teams`                     | object                       |
| `themes()` / `themes(date)`   | `GET /v1/reports/themes`                    | object                       |
| `themeConversations(themeId)` | `GET /v1/reports/themes/{id}/conversations` | object                       |
| `voice()`                     | `GET /v1/reports/voice`                     | object                       |

Two things are true of the nine extended reports and of none of the first four.

**The window is clamped, not validated.** Every `?days` / `?weeks` is parsed as
`min(max, max(min, parseInt(raw) || default))`, so `?days=9999` answers a 200 over the maximum
window and `?days=soon` answers a 200 over the _default_ one. There is no error to catch, and the
period you asked for is not necessarily the period you got. `cohorts()` echoes the `weeks` it
actually used — label your axes from that, not from your input. (`chatly.bots().analytics()` is the
one window that refuses a non-numeric value, and it refuses `from`/`to`/`weeks`/`date` by name.)

**None of them pages**, and each is capped with a remainder bucket rather than a cursor:
`labels()` keeps the 50 busiest with the rest in `otherLabels`, `attribution()` sums the tail into
one row, `themes()` caps a batch at 20. There is nothing to page to.

`themes(date)` takes `YYYY-MM-DD` and reaches a `date` _column_: `?date=yesterday` used to arrive at
Postgres untouched and come back as a 500 about invalid input syntax. It is validated now.
`themeConversations` answers 200 with an empty list for an unknown or purged theme id, so an empty
result is not evidence the id was valid. Voice rates are 0-1, not percentages, and
`recordingStorage.exact: false` means the byte count is part extrapolation.

### `chatly.channels()`

| Method                        | HTTP                                   |
| ----------------------------- | -------------------------------------- |
| `list()` → `List`             | `GET /v1/channels`                     |
| `get(channelId)`              | `GET /v1/channels/{id}`                |
| `adapters()` → `List<String>` | `GET /v1/channels/adapters`            |
| `create(Map)`                 | `POST /v1/channels`                    |
| `update(channelId, Map)`      | `PATCH /v1/channels/{id}`              |
| `rotateSecret(channelId)`     | `POST /v1/channels/{id}/secret/rotate` |

This section used to say channels were read-only from a key because "`channels:write` … no
key-accessible route grants" it. That was wrong: the three writes above all carry `@AllowApiKey()`
and all take `channels:write`.

`adapters()` is the **one route on this surface whose array holds strings** rather than objects
(`z.array(z.string())`), which is why it returns `List<String>`. Read it before `create`: the type
enum in the request schema is a superset of what a deployment has an adapter registered for, and a
type with no adapter is a channel that saves and never delivers.

`type` and `publicId` cannot be changed after creation — `update` does not accept them. `config` is
**merged**, not replaced.

`rotateSecret` is only meaningful for a custom (`api`) channel; every other type's credential is
issued by the provider, so it answers **409** for them rather than pretending to rotate something.
It is a hard cut with no grace window.

Worth reading `originsRestricted` on a web channel: a channel with no origin allow-list accepts an
embed from **any** site, and an empty list looks identical to an absent one in raw JSON, so the API
sends that boolean to make the difference visible.

### `chatly.billing()`

| Method                                         | HTTP                                |
| ---------------------------------------------- | ----------------------------------- |
| `plans()` → `List`                             | `GET /v1/billing/plans`             |
| `usage(eventType)` / `usage(eventType, Query)` | `GET /v1/billing/usage?eventType=…` |
| `summary()`                                    | `GET /v1/billing`                   |
| `invoices()` / `invoices(Query)`               | `GET /v1/billing/invoices`          |
| `usageDaily(eventType[, Query])`               | `GET /v1/billing/usage/daily`       |
| `usageMonthly([Query])`                        | `GET /v1/billing/usage/monthly`     |

`invoices` is **cursor-paginated and deliberately not a `Page`**: its envelope is
`{data, nextCursor}` — `data`, not `items` — because it predates the shared pagination helper. So
there is no `invoicesAuto`, and `Page.fromDecoded` refuses the body rather than reading an empty
page from it. Walk it with `page.get("nextCursor")` yourself.

`usageDaily` is dense: one entry per UTC day in `[from, to)`, zeros included. `usageMonthly` takes
no `eventType` — it returns one entry per known meter, always all of them, which is what makes it
comparable month to month.

Everything here needs `workspace:billing:read`, which is also the scope on `chatly.ai().quota()`.

`eventType` is required and must be a known meter — an unrecognised value is a 400, not an empty
result. The list is in the dashboard under **Settings → Billing → Usage** and is deliberately not
hardcoded here; an SDK-side enum goes stale the first time a meter is added and then rejects a value
the API accepts.

### `chatly.workspace()`

| Method                         | HTTP                         |
| ------------------------------ | ---------------------------- |
| `settings()`                   | `GET /v1/workspace/settings` |
| `labels()` → `List`            | `GET /v1/labels`             |
| `inboxes()` → `List`           | `GET /v1/inboxes`            |
| `teamMembers(teamId)` → `List` | `GET /v1/teams/{id}/members` |
| `agentSkills()`                | `GET /v1/agents/skills`      |
| `keyScopes()` → `List`         | `GET /v1/api-keys/scopes`    |
| `secrets()`                    | `GET /v1/workspace/secrets`  |
| `mine()` → `List`              | `GET /v1/workspaces/me`      |

`mine()` is **always exactly one entry for a key**: the workspace it was minted in. The route is
the cross-workspace one, so for a single-workspace credential it is a list of one — which makes it
the cheapest way to confirm which tenant a key actually points at before writing to it. The `role`
on that entry is the synthetic `restricted` minted for every key, not a stored membership role:
what a key may do is its scope set, and `chatly.me()` answers that. It replies even when the active
workspace is suspended, because it is how a caller discovers the others.

`keyScopes()` and `whoami()` are the only two routes on the api-keys controller a key may call —
managing keys with a key is a privilege-escalation shape. Together they turn "why am I getting a
403?" into a lookup, which is why the scopes route was opened: closed, its own 403 pointed the
caller at the endpoint it was refusing.

`secrets()` is the only method on this surface needing **two** scopes — `workspace:write` _and_
`integrations:read` — so a key with one gets a 403 that does not say which is missing. It carries no
secret, no prefix, no hash, no length. Read `rotatedAtBasis` and `lastUsedBasis` before acting on a
null timestamp: they say whether null means "never" or "nothing records it".

`agentSkills()` returns an **object**, not an array — `{"skills": ["billing", "german"]}` — the
distinct skill labels in use across the workspace, which is what skill-based routing matches against.

**There is no method listing every team, or every agent, and that is deliberate.** `GET /v1/teams`
carries no `@AllowApiKey()`, so it requires a signed-in person and answers every API key with 403
whatever its scopes; `GET /v1/agents` does not exist for _any_ caller, including an admin session —
the agents controller declares only `/v1/agents/skills` and `/v1/agents/{userId}/skills`. Both were
on an earlier draft of the shared surface list, and both would have been methods that always fail,
which is worse than no method at all. So you need a team id from elsewhere (the dashboard, or a
conversation's assignment) to call `teamMembers`.

For the skills of one _specific_ agent, that route exists and accepts a key but is not on the typed
surface — reach it through the escape hatch:

```java
chatly.request("GET", "/v1/agents/" + Query.encodePathSegment(userId) + "/skills");
```

Looking for per-agent **metrics**? That is `chatly.reports().agents()`. The two are easy to confuse
because the paths differ by one prefix.

### `chatly.ai()`

| Method                          | HTTP                                    |
| ------------------------------- | --------------------------------------- |
| `providers()`                   | `GET /v1/ai-providers`                  |
| `addProvider(provider, apiKey)` | `POST /v1/ai-providers`                 |
| `testProvider(provider)`        | `POST /v1/ai-providers/{provider}/test` |
| `quota()`                       | `GET /v1/ai/quota`                      |
| `tone()` → `List`               | `GET /v1/ai/tone`                       |
| `setTone(List<Map>)` → `List`   | `PUT /v1/ai/tone`                       |
| `generateTone(styleGuide)`      | `POST /v1/ai/tone/generate`             |
| `tonePreview()`                 | `GET /v1/ai/tone/preview`               |
| `tools()` → `List`              | `GET /v1/ai/tools`                      |
| `createTool(Map)`               | `POST /v1/ai/tools`                     |
| `updateTool(toolId, Map)`       | `PATCH /v1/ai/tools/{id}`               |
| `deleteTool(toolId)`            | `DELETE /v1/ai/tools/{id}`              |

`quota()` needs `workspace:billing:read`, not `ai:read` — reading your AI spend is a billing
question. It meters **subsidised** spend only; bring-your-own-key spend is recorded against a
different owner and is not bounded by the cap.

`addProvider` validates the key with a real call before storing it, and only an
authentication-class answer refuses the save. A provider that is down leaves you with a stored key
and `validation.status: "unverified"` — "nobody has proven this yet", not "this failed". Branch on
`validation.status`, not on the 201.

`setTone` is a **replace**: rules absent from the list are deleted, and an empty list clears the
workspace's tone. Read `tone()` first if you mean to add one rule. `generateTone` saves nothing — it
proposes rules for a human to approve with `setTone`.

`updateTool`: omit `secretHeaders` to keep the stored values. Sending `{}` clears every secret the
tool had and the next agent call goes out unauthenticated against your endpoint.

### `chatly.bots()`

| Method                                                     | HTTP                                            |
| ---------------------------------------------------------- | ----------------------------------------------- |
| `list()` / `list(Query)` → `Page`, `listAuto(…)`           | `GET /v1/bots`                                  |
| `get(botId)`                                               | `GET /v1/bots/{id}`                             |
| `create(Map)`                                              | `POST /v1/bots`                                 |
| `update(botId, Map)`                                       | `PATCH /v1/bots/{id}`                           |
| `analytics(botId, days)` / `analytics(botId, Query)`       | `GET /v1/bots/{id}/analytics`                   |
| `deliveries(botId[, Query])` → `Page`, `deliveriesAuto(…)` | `GET /v1/bots/{id}/deliveries`                  |
| `delivery(botId, deliveryId)`                              | `GET /v1/bots/{id}/deliveries/{did}`            |
| `redeliver(botId, deliveryId)`                             | `POST /v1/bots/{id}/deliveries/{did}/redeliver` |
| `testDelivery(botId)`                                      | `POST /v1/bots/{id}/test-delivery`              |
| `updateExternal(botId, Map)`                               | `PATCH /v1/bots/{id}/external`                  |
| `rotateExternalSecret(botId[, Instant activateAt])`        | `POST /v1/bots/{id}/external/rotate-secret`     |

Two kinds of row live here: a **Chatly persona** (a prompt and a model we run) and an **external
agent** (`type: "external"` — a webhook we POST a signed payload to and wait for). `botType` is the
discriminator, and the four delivery methods mean nothing for a persona.

`create` takes a union of two bodies and **neither half is strict**, so a field from the wrong one
is _dropped_: send a persona's `systemPrompt` with `type: "external"` and you get a 201 and no
prompt. Check the returned row. An external agent's response is the only one that ever carries its
two credentials, each shown once.

`testDelivery` writes nothing — no ledger row, no conversation, no retry — and is the first call to
make when building an external agent: it reports what your endpoint answered, the signature header
we sent, and the exact payload that was signed.

`redeliver` sends the **same bytes, same `eventId`**, so a handler that dedupes on `eventId` will
discard it. That is correct; a replay is for a delivery you never processed.

`rotateExternalSecret(botId)` is a hard cut: the new secret signs every delivery from the moment it
returns, so deploy it to your verifier first. `rotateExternalSecret(botId, activateAt)` gives you a
window instead — the new secret is returned now, but the **old one keeps signing every delivery
until `activateAt`** (at most 168 hours ahead; the server refuses more with a 400, and an instant
already past is a hard cut). Each delivery carries one signature, so hold both secrets in your
verifier for the window. The response's `activatesAt` is the instant of the switch, or `null` when
the new secret is already signing.

`analytics` percentiles are p50/p95/max and never a mean; a `null` rate belongs to the other bot
kind and is not zero.

### `chatly.botRoutes()`

| Method                 | HTTP                          |
| ---------------------- | ----------------------------- |
| `list()` → `List`      | `GET /v1/bot-routes`          |
| `create(Map)`          | `POST /v1/bot-routes`         |
| `update(routeId, Map)` | `PATCH /v1/bot-routes/{id}`   |
| `delete(routeId)`      | `DELETE /v1/bot-routes/{id}`  |
| `fields()`             | `GET /v1/bot-routes/fields`   |
| `resolve(Map)`         | `POST /v1/bot-routes/resolve` |

The table is ordered and first-match-wins; `list()` returns it in evaluation order. A route with no
conditions matches everything, so one of those near the top is the usual reason a later rule never
fires.

`resolve` is a read dressed as a POST and writes nothing. It returns the selected route, every
route with its verdict, and — the useful part — **the event object the conditions were evaluated
against**. Half of every routing bug is a field path that resolves to nothing.

`fields()` is the authoritative vocabulary: a condition whose `field` is not in it never matches,
and saving it says nothing.

### `chatly.brands()`

| Method                 | HTTP                     |
| ---------------------- | ------------------------ |
| `list()` → `List`      | `GET /v1/brands`         |
| `create(Map)`          | `POST /v1/brands`        |
| `update(brandId, Map)` | `PATCH /v1/brands/{id}`  |
| `delete(brandId)`      | `DELETE /v1/brands/{id}` |

`slug` is required on create and is **not** derived from the name: lowercase alphanumerics and
hyphens, rejected rather than normalised. `primaryColor` is strict `#RRGGBB`.

`delete` is a soft delete and answers 200 with the id — historical conversations keep their brand,
so last quarter's report still renders a brand retired yesterday. `list` carries `usage` counts,
which is what makes a brand safe to retire.

### `chatly.canvasSteps()`

| Method                 | HTTP                                 |
| ---------------------- | ------------------------------------ |
| `get(stepId)`          | `GET /v1/canvas-steps/{id}`          |
| `resolve(stepId, Map)` | `POST /v1/canvas-steps/{id}/resolve` |

The conversation and the message come from the step id, never from your request. A 409 means the
step was already resolved or expired — exactly one resolve wins. A 404 includes resolving from
_inside_ the request that answered `{pending: true}`: the row is written after we answer the
visitor, so return first and resolve afterwards.

### `chatly.customerCards()`

| Method                 | HTTP                                         |
| ---------------------- | -------------------------------------------- |
| `list()` → `List`      | `GET /v1/customer-cards`                     |
| `create(Map)`          | `POST /v1/customer-cards`                    |
| `update(cardId, Map)`  | `PATCH /v1/customer-cards/{id}`              |
| `rotateSecret(cardId)` | `POST /v1/customer-cards/{id}/rotate-secret` |
| `delete(cardId)`       | `DELETE /v1/customer-cards/{id}`             |

`create` is the only response carrying `secret`. The url is checked against the outbound guard at
write time — our server fetches it — and a reserved header name (`x-chatly-*`, `content-type`,
`host`, …) is refused: those are how your server knows the request is ours and which card's secret
signed it.

`update` is strict, and `headers: {}` **clears** every header rather than meaning "unchanged".

`rotateSecret` has no overlap. If you cannot redeploy atomically, use `customerCardEndpoints()`
instead — its rotation has a window.

### `chatly.customerCardEndpoints()`

| Method                       | HTTP                                                    |
| ---------------------------- | ------------------------------------------------------- |
| `list()` → `List`            | `GET /v1/customer-card-endpoints`                       |
| `create(url)`                | `POST /v1/customer-card-endpoints`                      |
| `rotateSecret(endpointId)`   | `POST /v1/customer-card-endpoints/{id}/rotate-secret`   |
| `activateSecret(endpointId)` | `POST /v1/customer-card-endpoints/{id}/activate-secret` |
| `delete(endpointId)`         | `DELETE /v1/customer-card-endpoints/{id}`               |

One secret signs every card pointing at one url, and `x-chatly-card-id` stops being sent. **That is
a change at your verifier in both directions** — `delete` reverts to per-card secrets signed by the
_leading_ card of the group, which is a cutover, not a cleanup.

`rotateSecret` mints the next secret with `active: false`; the current one keeps signing until
`activateSecret`. Calling it twice replaces the pending secret rather than stacking one.

### `chatly.docsSources()`

| Method                   | HTTP                                 |
| ------------------------ | ------------------------------------ |
| `providers()` → `List`   | `GET /v1/docs-sources/providers`     |
| `list()` → `List`        | `GET /v1/docs-sources`               |
| `discover(Map)` → `List` | `POST /v1/docs-sources/discover`     |
| `create(Map)`            | `POST /v1/docs-sources`              |
| `update(sourceId, Map)`  | `PATCH /v1/docs-sources/{id}`        |
| `sync(sourceId[, full])` | `POST /v1/docs-sources/{id}/sync`    |
| `webhook(sourceId)`      | `POST /v1/docs-sources/{id}/webhook` |
| `delete(sourceId)`       | `DELETE /v1/docs-sources/{id}`       |

`discover` is a POST that answers a **bare array** — one of two on this surface. Pass
`organizationId` when your token is org-scoped: without it, discovery fails for such a token with an
error naming a path you never asked about, and an account-wide token returns every space in every
organisation.

`sync(id, true)` resets the checkpoint and re-reads the whole space. That is the one to use when the
_reader_ changed rather than the content: an ordinary sync answers with a delta, so after a
provider-side fix the run finds nothing new and the knowledge base stays as wrong as it was.

`webhook` answers 201 with `subscribed: false` and a reason when the stored token cannot manage
webhooks, which is the common case. A refusal here is a success — branch on `subscribed`.

`delete` keeps the articles (`kept` counts them). They stop being refreshed, so a disconnected
source leaves a knowledge base that looks current and is frozen.

### `chatly.emailTemplates()`

| Method                         | HTTP                                |
| ------------------------------ | ----------------------------------- |
| `list()` → `List`              | `GET /v1/email-templates`           |
| `preview(kind, subject, body)` | `POST /v1/email-templates/preview`  |
| `update(kind, subject, body)`  | `PUT /v1/email-templates/{kind}`    |
| `delete(kind)`                 | `DELETE /v1/email-templates/{kind}` |

Five kinds, always all five: `workspace_invite`, `trial_ending_reminder`,
`conversation_cap_warning`, `hired_agent_engagement_invite`, `hired_agent_engagement_started`. The
kind is validated in the path, so an unknown one is a 400 naming it.

`preview` renders through the real send-time renderer and reports **tokens you wrote that this kind
does not supply** — those render as nothing in the delivered mail, silently, and this is the only
place that tells you.

`delete` has a body, not a 204: `{"restored": false}` with a 200 means the workspace was already on
the built-in. It deletes the override rather than copying today's default into it.

### `chatly.knowledgeGaps()`

| Method                            | HTTP                                 |
| --------------------------------- | ------------------------------------ |
| `list()` / `list(Query)` → `List` | `GET /v1/knowledge-gaps`             |
| `update(gapId, status)`           | `PATCH /v1/knowledge-gaps/{id}`      |
| `draft(gapId)`                    | `POST /v1/knowledge-gaps/{id}/draft` |

Most-asked first, and complete by construction: the recorder caps a workspace at 200 open gaps, so a
workspace stuck at 200 is at the cap rather than mid-page.

The `?status` **filter is ignored when unrecognised** rather than refused — the handler `safeParse`s
it — so a typo returns every status with a 200. The body on `update` is strict: there, an unknown
status is a 400.

`draft` writes a **draft** article, invisible in any help centre until published, and answers 201
with either the new id or a reason there is none.

### `chatly.triggers()`

| Method                                           | HTTP                       |
| ------------------------------------------------ | -------------------------- |
| `list()` / `list(Query)` → `Page`, `listAuto(…)` | `GET /v1/triggers`         |
| `create(Map)`                                    | `POST /v1/triggers`        |
| `delete(triggerId)`                              | `DELETE /v1/triggers/{id}` |

`create` is an **upsert**: pass `id` and it replaces that trigger, omit it and one is created —
which is why there is no PATCH, and why a stale `id` in a config file overwrites rather than
conflicts.

`actions` must hold 1 to 20 entries; a trigger that does nothing is refused. `conditions` is
optional and empty means "every occurrence of this event". An event name nothing publishes saves
fine and never fires, so if a trigger never runs, check the name before the conditions.

### `chatly.visitors()`

| Method                         | HTTP                                               |
| ------------------------------ | -------------------------------------------------- |
| `startConversation(contactId)` | `POST /v1/visitors/{contactId}/start-conversation` |

Opens a conversation with a contact who did **not** write first, and answers
`{"conversationId": "…"}`. No body and no query parameters — the contact id is the whole input.

**It is off by default, per workspace.** The flag is
`settings.proactiveOutreach.apiKeysMayStartConversations`; an owner or admin turns it on with
`PATCH /v1/workspace/settings`, which needs a signed-in person and is therefore not in this SDK,
and you can read its current value with `chatly.workspace().settings()`, which is. So a
`PermissionException` here is more often that setting than your key's scopes: `conversations:write`
was already grantable and is already held by keys minted for ticket bulk actions and schedule-send,
so opening this route on the permission alone would have handed cold outreach to credentials nobody
re-reviewed. Check the setting first.

**Three per contact per hour**, counted per workspace and per _contact_ rather than per caller —
the abuse shape is N threads aimed at one person, which a per-caller limit cannot see. That refusal
is a `RateLimitException` whose `retryAfter()` is **empty**: the TTL arrives in the error body at
`error.details.retryAfterSeconds` and no `Retry-After` header is sent. The global throttler buckets
this route also inherits do send one, so `retryAfter()` is populated or empty depending on which
limit refused — code that backs off only when it is present will hot-loop against the per-contact
one.

**An offline visitor is not notified.** The greeting is persisted and the widget renders it from
history on their next page view; no email, no push, no provider message. An integration that needs
to reach someone who has already left the site wants an email or SMS conversation instead.

The other two routes on that controller — `GET /v1/visitors/live` and
`GET /v1/visitors/{contactId}/pages` — carry no `@AllowApiKey()`, so they are not here.

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

| Type                      | When                                                                                                                  |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `AuthenticationException` | 401 — key missing, malformed, revoked                                                                                 |
| `PermissionException`     | 403 — key valid, scope missing, or route needs a human                                                                |
| `NotFoundException`       | 404 — no such row, **or** it is in another workspace                                                                  |
| `ConflictException`       | 409 — conflict, including an idempotency-key/body mismatch                                                            |
| `ValidationException`     | 400 — payload failed the API's schema; also 422, which a few routes use for a well-formed request rejected on content |
| `RateLimitException`      | 429 — carries `retryAfter()`                                                                                          |
| `ServerException`         | 5xx — already retried before it reached you                                                                           |
| `TransportException`      | DNS, connect, TLS, timeout, interrupt. `status()` is `0`                                                              |
| `ChatlyException`         | any other non-2xx, and the base type to catch                                                                         |

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
_accepted_ webhook in a handler that forgot a `try/catch` — the exception becomes a 500, our
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

### Every other surface: `verifyChatlySignature`

`verifySignature` above knows one header. Chatly signs **eight** kinds of outbound request, and the
header name does not tell you which construction you are looking at: three of them send
`x-chatly-signature` and sign three different strings, and two send their timestamp in
**milliseconds**. So the general verifier takes the surface from you rather than guessing:

```java
ChatlySignatureResult r = Webhooks.verifyChatlySignature(
    ChatlySignatureInput.builder()
        .surface(ChatlySignatureSurface.AGENT_DELIVERY)
        .body(rawBody)                          // byte[] as received, or a String (UTF-8)
        .headers(request.headers().map())       // any Map<String, ? extends Collection<String>>
        .secret(System.getenv("CHATLY_BOT_SIGNING_SECRET"))
        .method(request.method())               // pass these on EVERY surface — see below
        .path(pathAndQuery)                     // pathname + search, never scheme or host
        .build());

if (!r.ok()) {
    log.warn("refused {}: {}", r.reasonCode(), r.message());
    return ResponseEntity.status(400).build();
}
// r.surface(), r.timestampMs(), r.base(), r.secretIndex()
```

| `ChatlySignatureSurface`  | header                                                | signed string                        | `t` unit |
| ------------------------- | ----------------------------------------------------- | ------------------------------------ | -------- |
| `UNIFIED_V2`              | `x-chatly-signature-v2: t=…,v1=…`                     | `<t>.<METHOD>.<path>.<rawBody>`      | seconds  |
| `AGENT_DELIVERY`          | `x-chatly-signature: t=…,v1=…`                        | `<t>.<rawBody>`                      | seconds  |
| `EVENT_WEBHOOK`           | `x-livechat-signature: t=…,v1=…`                      | `<t>.<rawBody>`                      | seconds  |
| `AI_TOOL_CALL`            | `x-chatly-signature: t=…,v1=…`                        | `<t>.<METHOD>.<path>.<rawBody>`      | seconds  |
| `CANVAS_STEP`             | `x-chatly-canvas-signature: sha256=…` + `-ts`         | `<ts>.<rawBody>`                     | **ms**   |
| `CUSTOMER_CARD`           | `x-chatly-signature: sha256=…` + `x-chatly-timestamp` | `<ts>.<rawBody>`                     | **ms**   |
| `WORKFLOW_WEBHOOK`        | `x-chatly-signature-v2: t=…,v1=…`                     | `<t>.<rawBody>` (or the target base) | seconds  |
| `WORKFLOW_WEBHOOK_LEGACY` | `x-chatly-signature: sha256=…`                        | `<rawBody>` — **no timestamp**       | n/a      |

**It returns a reason, not a boolean.** One of eleven stable codes — `unknown_surface`,
`missing_secret`, `missing_signature_header`, `duplicate_signature_header`,
`malformed_signature_header`, `missing_timestamp`, `malformed_timestamp`,
`timestamp_outside_tolerance`, `missing_request_target`, `replay_unprotected`,
`signature_mismatch` — plus a `message` written to be printed. "Your clock is wrong" and "your secret
is wrong" are different support tickets, and a `false` cannot tell them apart. Like
`verifySignature`, it **never throws**, for any input including `null`.

**The unified v2 header.** Every surface is moving to `x-chatly-signature-v2` over
`<t>.<METHOD>.<path>.<rawBody>`, sent beside its legacy header. The default
`V2Policy.PREFER` verifies the v2 header whenever it is present **and** you passed `method` and
`path`, and the legacy header otherwise — so passing both on every surface gets you the stronger
check the day the sender starts emitting it, with no code change. Once v2 is chosen it never falls
back: a bad v2 digest beside a good legacy one is refused, and `r.surface()` reads `unified_v2` on
a v2 success. `REQUIRE` refuses without v2; `IGNORE` reads only the legacy header.

**Key rotation, both ends.** `.secrets(oldSecret, newSecret)` accepts either, and
`r.secretIndex()` says which verified — an index into the list **you passed**, empties included,
which is how you learn the old secret is finally unused. A `t=…,v1=…,v1=…` header from a sender
mid-rotation verifies when any digest matches.

**Refused on purpose:** a signature header that arrived twice (including under two casings —
lookup is case-insensitive and collects across keys), which is a proxy merging or injecting one;
a timestamp more than 300 s out in **either** direction (`toleranceSeconds(...)` changes it, exactly
300 s is inside); and `WORKFLOW_WEBHOOK_LEGACY`, which can be replayed forever, unless you call
`allowUnprotectedReplay(true)` — verify that call's v2 header as `WORKFLOW_WEBHOOK` instead.

Behaviour is pinned by `src/test/resources/signature_vectors.json`, a corpus shared byte-for-byte by
all eight backend SDKs; `ChatlySignatureTest` runs every vector in it.

### Minting one, to test your own handler: `signChatlyV2`

The other direction, for a test that stands up a fake Chatly and fires a signed request at your own
endpoint. Until this existed you had to re-derive `<t>.<METHOD>.<path>.<rawBody>` by hand from the
documentation — and a hand-derived base string is the one thing a signature test must not have: get
it wrong the same way in the test and nowhere else, and the test passes while production refuses
every delivery.

```java
String header = Webhooks.signChatlyV2(
    ChatlySignatureMintInput.builder()
        .base(ChatlySignatureMintInput.Base.TARGET)   // TARGET: <t>.<METHOD>.<path>.<body>
        .secret(secret)                               // BODY:   <t>.<body>
        .timestampSeconds(Instant.now().getEpochSecond())   // SECONDS, never milliseconds
        .method("POST")
        .path("/hooks/chatly?run=7")                  // pathname + query, never the host
        .body(rawBody)                                // byte[] or String
        .build());
// t=1760000000,v1=<64 hex>,b=t
```

`base` has **no default**: the two constructions are indistinguishable from the value alone, which
is precisely why the `b=` marker exists, so a caller has to say which one they mean. `TARGET` emits
`b=t` and `BODY` emits `b=b`; this never emits an absent marker, which on the wire means `BODY`.
`Webhooks.chatlyV2BaseString(input)` returns the exact bytes the MAC is taken over, so a test can
pin the construction and not only the digest.

Named setters rather than six positional arguments, because the construction is three strings in a
row and `signChatlyV2(secret, t, method, path, body)` compiles with `path` and `body` swapped — it
then mints a digest nobody can reproduce, which arrives at the receiver as "signature mismatch" and
sends whoever wrote it hunting their own secret. For the same reason `build()` **throws** on an
input it cannot sign faithfully (no base, an empty secret, a negative timestamp, a null body, or a
`TARGET` with no method or path), where the verifier never throws: a verifier that throws is an
accepted webhook in a handler that forgot to catch, but a mint that quietly signs the wrong string
is a test that passes while production fails.

The one wire value it will not produce on its own is the workflow action's current **dual-digest**
form, `t=<T>,v1=<target>,v1=<body>` with no marker. Build it from two mints by taking the `v1=`
field out of each — which is exactly what the producer does, and what
`ChatlySignatureMintTest` asserts.

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
    externalId: '[[${user.id}]]',
    userHash: '[[${chatlyUserHash}]]',
    email: '[[${user.email}]]',
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

174 API routes accepted an API key when the shared manifest was last audited against the handlers,
and all 163 on that list have a typed method above. For whatever is not on it — routes have been
opened since that count was written down, three of which (`DELETE /v1/webhooks/{id}`, `GET /v1/me`,
`GET /v1/workspaces/me`) have since reached the manifest and are methods now:

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

First, zero dependencies means no reflective binder — and hand-written response classes for 163
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

240 JUnit 5 tests across thirteen classes (measured 2026-10-15; one per signature-corpus vector,
so the figure grows with the corpus). Nothing in the suite touches the public network, needs
an API key, or needs a running API: HTTP is driven either by an injected `Transport.Sender` or by a
loopback `com.sun.net.httpserver.HttpServer` on an ephemeral port.

```
mvn test
```

Both transports are used on purpose, because they catch different things: an injected sender
validates the request the SDK _intended_; only real HTTP validates the request it actually produced.

### `surface.lock.json`

`mvn test` regenerates [`surface.lock.json`](./surface.lock.json) by **calling all 163 methods** and
recording the `METHOD /path` each one actually issued, with interpolated segments normalised back to
the manifest's placeholder form. It then asserts that set equals
`packages/sdk-spec/surface.json` — the manifest shared by every Chatly SDK.

Each entry also carries a `shape`: the top-level shape of the route's successful response, one of
`array` (33 routes), `page` (13), `object` (115) and `empty` (1 — `macros.delete` is the only real
204 here; six other DELETEs answer 200 with a body). That column is **hand-written** in
[`ResponseShape`](./src/test/java/com/chatlychat/chatly/ResponseShape.java), from the handlers'
`@ZodResponse` schemas, and deliberately _not_ read from the manifest: a fixture derived from the
manifest agrees with the manifest even when the manifest is wrong, and the manifest has been wrong.
`macros.list` carried `paged: true` for a day while `GET /v1/macros` is `@PaginationExempt` and
answers a bare array; two sibling SDKs built paginators over it and raised a decode error against
the real API with green suites, because their fake transports answered _every_ route with
`{items, nextCursor}`. One of them read the bare array as an _empty page_ and returned it with a
200, which is a wrong answer rather than a missing error.

So the fake here answers each route with a body of **its own declared shape**, the suite asserts the
value each method hands back really has that shape — which is what catches a method that wraps or
unwraps even when the request it issued was perfect — and a separate test feeds all thirteen
auto-paginators a bare array and requires each to throw. One route gets a body of its own:
`channels.adapters` is an array of _strings_, so the shared `[{…}]` fixture could not exercise the
decoder whose job is to refuse exactly that. The override is capped at one entry by a test. The gate then diffs eight SDKs'
independently written columns against the manifest, so a wrong row would need eight authors to
misread the same handler.

The lock is committed, and regenerating it is part of the test run rather than a script someone
forgets. It exists because the cross-language parity gate used to match path strings in SDK _source
text_, which credited this SDK with every route on the manifest as it then stood — 55 of them —
while two of those methods did not exist: a Javadoc
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

49 assertions, exit code 0 or 1. It is a supplement, not a replacement — `mvn test` runs more than
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
