package com.chatlychat.chatly;

import java.util.Map;

/**
 * Visitors — opening a conversation with someone who has not written to you.
 *
 * <p>Reached as {@code chatly.visitors()}.
 *
 * <h2>One method, and why the other two routes on that controller are absent</h2>
 *
 * <p>{@code GET /v1/visitors/live} (who is on the site right now) and
 * {@code GET /v1/visitors/{contactId}/pages} (one visitor's recent page views) sit on the same
 * controller and carry <strong>no {@code @AllowApiKey()}</strong>. A {@code ck_} key gets a 403
 * from both, so they are not this SDK's surface: shipping a method that always fails is worse than
 * not shipping one, because the failure arrives at runtime, in production, inside someone else's
 * integration. Use {@link Chatly#request} if you have a session-backed proxy that can reach them.
 */
public final class VisitorsResource extends Resource {

  private static final String COLLECTION = "/v1/visitors";

  VisitorsResource(Transport transport) {
    super(transport);
  }

  /**
   * Open a conversation with a contact who did <strong>not</strong> write first.
   *
   * <p>{@code POST /v1/visitors/{contactId}/start-conversation} — scope
   * {@code conversations:write}. Answers {@code {"conversationId": "…"}}.
   *
   * <p>No request body and no query parameters: the contact id is the whole input. This method
   * therefore sends <em>no body at all</em> rather than {@code {}} — see the note at the bottom.
   *
   * <h2>A 403 here is probably not about your scopes</h2>
   *
   * <p>This is <strong>off by default, per workspace</strong>. The flag is
   * {@code settings.proactiveOutreach.apiKeysMayStartConversations}. An owner or admin turns it on
   * with {@code PATCH /v1/workspace/settings}, which needs a signed-in person and so is not
   * reachable from this SDK; you can read its current value with {@link WorkspaceResource#settings}
   * ({@code GET /v1/workspace/settings}), which is key-accessible.
   *
   * <p>So a {@link com.chatlychat.chatly.errors.PermissionException} from this method means one of
   * two quite different things, and the workspace setting is the likelier of the two. The scope is
   * not the opt-in: {@code conversations:write} was already grantable and is already held by keys
   * minted for ticket bulk actions and schedule-send, so opening this route on the permission alone
   * would have handed cold outreach to credentials nobody re-reviewed. <strong>Check the setting
   * before you go looking at your key.</strong>
   *
   * <h2>Rate limit: three per contact per hour, and its 429 has no {@code Retry-After}</h2>
   *
   * <p>Counted in Redis per workspace <em>and per contact</em> rather than per caller — the abuse
   * shape here is N threads aimed at one person, which a per-caller limit cannot see. The fourth
   * call inside an hour is a {@link com.chatlychat.chatly.errors.RateLimitException}.
   *
   * <p><strong>But {@link com.chatlychat.chatly.errors.RateLimitException#retryAfter()} is empty
   * for that one.</strong> This route can produce two different 429s and they do not carry the
   * same information:
   *
   * <ul>
   *   <li><strong>The per-contact limit.</strong> {@code consumePerContactOutreachToken} throws
   *       with the window's remaining TTL in the error <em>body</em>, at
   *       {@code error.details.retryAfterSeconds}, and the exception filter sets no headers at
   *       all. So no {@code Retry-After} reaches the client, {@code retryAfter()} is empty, and
   *       the TTL is readable only by parsing {@link
   *       com.chatlychat.chatly.errors.ChatlyException#body()}.
   *   <li><strong>The global throttler buckets</strong> this route inherits ({@code short},
   *       30/second; {@code login}, 100/15 minutes). Those come from {@code @nestjs/throttler},
   *       which does emit per-bucket headers that the API folds into a plain
   *       {@code Retry-After} — so {@code retryAfter()} is populated for those.
   * </ul>
   *
   * <p>A caller that backs off only when {@code retryAfter()} is present will therefore hot-loop
   * against the per-contact limit. Treat an empty {@code retryAfter()} on a 429 from this route as
   * "wait the hour", or read the body.
   *
   * <p>Measured, not assumed: {@code Transport.parseRetryAfter} reads the {@code retry-after}
   * header and has no {@code details.retryAfterSeconds} fallback, and
   * {@code VisitorsRequestTest} drives a real header-less 429 envelope through it. This is the one
   * route where CONTRACT.md &sect;10.4's promise — "a 429 the rate-limit type with
   * {@code retry_after} populated" — is language-dependent: the Ruby and Python clients do fall
   * back to the body and surface the TTL, while Java, Node, Go and PHP do not.
   *
   * <h2>An offline visitor is not notified</h2>
   *
   * <p>The greeting is persisted and the widget renders it from history on the contact's next page
   * view. No email, no push, no provider message; nothing is queued and nothing is dropped. An
   * integration that needs to reach somebody who has already left the site wants an email or SMS
   * conversation, not this.
   *
   * <h2>The idempotency key is required, not merely honoured</h2>
   *
   * <p>The controller declares {@code @RequireIdempotencyKey()} at class level and this handler
   * does not opt out, so the API answers <strong>428</strong> in production without the header.
   * {@link Transport} mints one per call; {@link Chatly#withIdempotencyKey} pins one if you want a
   * retry of the same logical outreach to replay rather than open a second thread.
   *
   * @param contactId the contact to open a thread with
   * @return {@code {"conversationId": "…"}}
   */
  public Map<String, Object> startConversation(String contactId) {
    String path = COLLECTION + "/" + seg(contactId, "contactId") + "/start-conversation";
    /*
     * `null`, not `Map.of()`.
     *
     * `Transport.execute` serialises whatever it is handed unless it is null:
     * `Map.of()` becomes the two bytes `{}` AND adds a `Content-Type:
     * application/json` header. The handler takes no `@Body()` at all, so the
     * honest request carries no entity — `BodyPublishers.noBody()` and no
     * content type, which is what passing null produces.
     *
     * The eleven other no-body POSTs in this package pass `Map.of()` and do
     * send `{}`. Harmless against a zod schema that strips unknown keys, but
     * it is not what the route declares, and it is not a pattern to copy into
     * a new method.
     */
    return object(transport.post(path, Query.of(), null), path);
  }
}
