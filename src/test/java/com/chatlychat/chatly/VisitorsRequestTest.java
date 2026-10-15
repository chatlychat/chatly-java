package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chatlychat.chatly.errors.PermissionException;
import com.chatlychat.chatly.errors.RateLimitException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code visitors.start_conversation} — asserted on what the SDK SENT, not on what it got back.
 *
 * <p>Every assertion here reads {@link FakeSender}'s recorded request. That distinction is the
 * whole point: a fake transport that answers each route with the shape the SDK asked for agrees
 * with whatever the SDK does, including when the SDK is wrong, which is how two sibling clients
 * shipped a paginator over a bare-array route with green suites. A canned
 * {@code {"conversationId": "cv_1"}} proves nothing about the request that fetched it.
 *
 * <p>This route is the one on the surface where the request has to be exactly right and there is
 * nothing in the response to notice it by: the only input is a path segment, so a wrong path is a
 * 404 rather than a validation error, and the {@code Idempotency-Key} is REQUIRED (the controller
 * is {@code @RequireIdempotencyKey()} at class level with no opt-out on this handler), so a
 * missing header is a 428 in production and invisible against any fake.
 */
@DisplayName("visitors.startConversation issues exactly one bodyless, keyed POST")
class VisitorsRequestTest {

  private Transport.Request issue() {
    FakeSender sender = new FakeSender().then(201, "{\"conversationId\":\"cv_9\"}");
    Map<String, Object> result = sender.client().visitors().startConversation("c_42");
    assertEquals(1, sender.callCount(), "one surface method is one request");
    assertEquals("cv_9", result.get("conversationId"));
    return sender.lastRequest();
  }

  @Test
  void itIsAPostToThePathWithTheContactIdInterpolated() {
    Transport.Request request = issue();
    assertEquals("POST", request.method());
    assertEquals(
        Chatly.DEFAULT_BASE_URL + "/v1/visitors/c_42/start-conversation", request.url());
  }

  @Test
  void itCarriesAnIdempotencyKey() {
    // Required, not optional. A UUID per call, minted by the transport.
    String key = issue().header("Idempotency-Key").orElse(null);
    assertTrue(
        key != null && key.matches("[0-9a-fA-F-]{36}"),
        "expected a UUID Idempotency-Key, got " + key);
  }

  @Test
  void itSendsNoRequestBodyAtAll() {
    /*
     * `null` body, not the two bytes `{}`.
     *
     * The handler declares no `@Body()`, so there is no entity to send. The
     * eleven other no-body POSTs in this package pass `Map.of()` to the
     * transport and do send `{}` with a `Content-Type` — harmless against a
     * zod schema that strips unknown keys, but not what the route declares,
     * and the thing this assertion exists to stop spreading.
     */
    Transport.Request request = issue();
    assertEquals(null, request.body(), "a route with no @Body() must send no entity");
    assertTrue(
        request.header("Content-Type").isEmpty(),
        "no body means no Content-Type either, got " + request.header("Content-Type"));
  }

  @Test
  void theWorkspaceOptInRefusalArrivesAsAPermissionErrorWithItsMessageIntact() {
    // A 403 here is more often the workspace opt-in than the key's scopes, so
    // the message has to survive: it is what tells an integrator to go and
    // look at `settings.proactiveOutreach.apiKeysMayStartConversations`.
    FakeSender sender =
        new FakeSender()
            .then(
                403,
                FakeSender.errorBody(
                    "forbidden",
                    "This workspace does not allow an API key to open conversations. An owner"
                        + " or admin enables it with `PATCH /v1/workspace/settings`"
                        + " `{ \"proactiveOutreach\": { \"apiKeysMayStartConversations\": true } }`."
                        + " Nothing was sent."));
    PermissionException e =
        assertThrows(
            PermissionException.class,
            () -> sender.client().visitors().startConversation("c_42"));
    assertTrue(e.getMessage().contains("apiKeysMayStartConversations"), e.getMessage());
    // A 403 is not retried — only 429 and 5xx are. Retrying an opt-in
    // refusal is three audit entries for one mistake.
    assertEquals(1, sender.callCount());
  }

  /**
   * The per-contact limit's 429, as the server really sends it — no {@code Retry-After}.
   *
   * <p>This test asserted a {@code retry-after: 1800} header until it was measured. It does not
   * exist on this refusal: {@code consumePerContactOutreachToken} throws a {@code RateLimitedError}
   * whose TTL lands in {@code error.details.retryAfterSeconds}, and
   * {@code app-exception.filter.ts} sets no response headers at all — it sends {@code wireError}
   * and nothing else. The only thing that synthesises a plain {@code Retry-After} is a Fastify
   * {@code onSend} hook that folds {@code @nestjs/throttler}'s per-bucket headers, and this
   * refusal never goes near the throttler.
   *
   * <p>So the honest fixture is a 429 with no header, and the honest assertion is that
   * {@link RateLimitException#retryAfter()} is <em>empty</em> while the TTL is still readable
   * from the body. A fixture that invents the server's shape makes the suite agree with the bug,
   * which is the trap this whole file is arranged against — and inventing a header here would
   * have had the SDK's author believing a backoff value was available that is not.
   */
  @Test
  void thePerContactHourlyLimit429CarriesNoRetryAfterAndTheTtlOnlyInTheBody() {
    FakeSender sender =
        new FakeSender()
            .then(
                429,
                Json.write(
                    Map.of(
                        "error",
                        Map.of(
                            "code", "rate_limited",
                            "message", "Too many requests",
                            "details", Map.of("retryAfterSeconds", 1800)))));
    // `clientNoRetries()`, not `client()`: a 429 IS retryable, so the retrying
    // client consumes the one scripted response and fails inside FakeSender
    // before reaching the assertion — and honours a 30-second Retry-After for
    // real while doing it. Retry behaviour is covered in ClientContractTest;
    // what is under test here is the mapping of this route's two 429s.
    RateLimitException e =
        assertThrows(
            RateLimitException.class,
            () -> sender.clientNoRetries().visitors().startConversation("c_42"));

    assertEquals(429, e.status());
    assertTrue(
        e.retryAfter().isEmpty(),
        "the per-contact limit sends no Retry-After, so retryAfter() must be empty — got "
            + e.retryAfter());

    // The TTL is still recoverable, from the body and only from the body.
    Map<String, Object> envelope = Json.parseObject(e.body().orElseThrow());
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) envelope.get("error");
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) error.get("details");
    assertEquals(1800L, ((Number) details.get("retryAfterSeconds")).longValue());
  }

  /**
   * And the other 429 this route can produce — a throttler bucket — does carry the header.
   *
   * <p>Kept beside the one above so the pair states the real behaviour: {@code retryAfter()} is
   * populated or empty depending on WHICH limit refused, and a caller that backs off only when
   * it is populated will hot-loop against the per-contact one.
   */
  @Test
  void aThrottlerBucket429OnTheSameRouteDoesCarryRetryAfter() {
    FakeSender sender =
        new FakeSender()
            .then(
                429,
                FakeSender.errorBody("rate_limited", "Too many requests"),
                Map.of("retry-after", List.of("30")));
    // `clientNoRetries()`, not `client()`: a 429 IS retryable, so the retrying
    // client consumes the one scripted response and fails inside FakeSender
    // before reaching the assertion — and honours a 30-second Retry-After for
    // real while doing it. Retry behaviour is covered in ClientContractTest;
    // what is under test here is the mapping of this route's two 429s.
    RateLimitException e =
        assertThrows(
            RateLimitException.class,
            () -> sender.clientNoRetries().visitors().startConversation("c_42"));
    assertEquals(Duration.ofSeconds(30), e.retryAfter().orElse(null));
  }
}
