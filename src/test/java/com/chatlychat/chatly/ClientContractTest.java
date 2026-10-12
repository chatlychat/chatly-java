package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chatlychat.chatly.errors.AuthenticationException;
import com.chatlychat.chatly.errors.ChatlyException;
import com.chatlychat.chatly.errors.ConflictException;
import com.chatlychat.chatly.errors.NotFoundException;
import com.chatlychat.chatly.errors.PermissionException;
import com.chatlychat.chatly.errors.RateLimitException;
import com.chatlychat.chatly.errors.ServerException;
import com.chatlychat.chatly.errors.TransportException;
import com.chatlychat.chatly.errors.ValidationException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * CONTRACT.md §10, items 1 through 5: construction, auth, idempotency, error types, retries.
 *
 * <p>Items 6 through 9 are in {@link PaginationTest}, {@link WebhooksTest} and {@link IdentityTest}.
 */
class ClientContractTest {

  @Nested
  @DisplayName("§10.1 the default base URL is the production host")
  class DefaultBaseUrl {

    @Test
    void constantIsProduction() {
      assertEquals("https://api.chatlychat.com", Chatly.DEFAULT_BASE_URL);
    }

    /**
     * The assertion with teeth.
     *
     * <p>Asserting on the constant alone is what every one of the four SDKs that shipped a
     * nowhere-resolving default already did. The defect was never in the constant; it was that the
     * constant was a dev host and no test ever looked at the URL a request actually went to, because
     * every test passed an explicit base URL. So: build a client the way the quickstart does, make a
     * call, and read the URL off the wire.
     */
    @Test
    void aClientBuiltTheWayTheQuickstartDoesCallsProduction() {
      FakeSender sender = new FakeSender().then(200, "{\"workspaceId\":\"ws_1\"}");
      Chatly chatly = Chatly.builder().apiKey("ck_test_key").sender(sender).build();

      chatly.whoami();

      assertEquals(
          "https://api.chatlychat.com/v1/api-keys/whoami", sender.lastRequest().url());
    }

    @Test
    void selfHostedBaseUrlIsHonouredAndTrailingSlashTrimmed() {
      FakeSender sender = new FakeSender().then(200, "{}");
      Chatly.builder()
          .apiKey("ck_test_key")
          .baseUrl("https://chatly.internal.acme.io/")
          .sender(sender)
          .build()
          .whoami();

      assertEquals(
          "https://chatly.internal.acme.io/v1/api-keys/whoami", sender.lastRequest().url());
    }

    @Test
    void emptyKeyIsRejectedAtConstructionNotAtFirstCall() {
      assertThrows(
          IllegalArgumentException.class, () -> Chatly.builder().apiKey("").build());
      assertThrows(
          IllegalArgumentException.class, () -> Chatly.builder().apiKey("   ").build());
      assertThrows(IllegalArgumentException.class, () -> Chatly.builder().build());
    }

    @Test
    void aBaseUrlWithoutASchemeIsRejected() {
      assertThrows(
          IllegalArgumentException.class,
          () -> Chatly.builder().apiKey("ck_x").baseUrl("api.chatlychat.com").build());
    }
  }

  @Nested
  @DisplayName("§10.2 Authorization carries the key")
  class AuthHeader {

    @Test
    void bearerTokenAndStandardHeaders() {
      FakeSender sender = new FakeSender().then(200, "{}");
      sender.client().whoami();

      Transport.Request req = sender.lastRequest();
      assertEquals("Bearer ck_test_key", req.header("authorization").orElseThrow());
      assertEquals("application/json", req.header("accept").orElseThrow());
      assertEquals("chatly-sdk-java/0.1.0", req.header("user-agent").orElseThrow());
    }

    @Test
    void contentTypeOnlyOnRequestsWithABody() {
      FakeSender sender = new FakeSender().then(200, "{}").then(200, "{}");
      Chatly chatly = sender.client();

      chatly.whoami();
      assertTrue(sender.requests.get(0).header("content-type").isEmpty());

      chatly.contacts().upsert(Map.of("externalId", "u-1"));
      assertEquals("application/json", sender.requests.get(1).header("content-type").orElseThrow());
    }

    @Test
    void aCallerProductCanBeAppendedToTheUserAgentButNotReplaceIt() {
      FakeSender sender = new FakeSender().then(200, "{}");
      Chatly.builder()
          .apiKey("ck_test_key")
          .sender(sender)
          .userAgent("acme-billing/2.1")
          .build()
          .whoami();

      assertEquals(
          "chatly-sdk-java/0.1.0 acme-billing/2.1",
          sender.lastRequest().header("user-agent").orElseThrow());
    }
  }

  @Nested
  @DisplayName("§10.3 idempotency keys")
  class Idempotency {

    @Test
    void aWriteSendsAnIdempotencyKey() {
      FakeSender sender = new FakeSender().then(201, "{\"id\":\"c_1\"}");
      sender.client().contacts().upsert(Map.of("externalId", "u-1"));

      String key = sender.idempotencyKeyOf(0);
      assertTrue(key != null && !key.isEmpty(), "a write must carry an Idempotency-Key");
      assertTrue(
          key.length() >= 16 && key.length() <= 128,
          "the API accepts 16-128 characters; got " + (key == null ? "null" : key.length()));
    }

    @Test
    void aReadSendsNoIdempotencyKey() {
      FakeSender sender = new FakeSender().then(200, "{}");
      sender.client().whoami();
      assertTrue(sender.requests.get(0).header("Idempotency-Key").isEmpty());
    }

    @Test
    void twoWritesSendDifferentKeys() {
      FakeSender sender = new FakeSender().then(201, "{}").then(201, "{}");
      Chatly chatly = sender.client();

      chatly.contacts().upsert(Map.of("externalId", "u-1"));
      chatly.contacts().upsert(Map.of("externalId", "u-2"));

      assertNotEquals(
          sender.idempotencyKeyOf(0),
          sender.idempotencyKeyOf(1),
          "a fresh key per call is what stops an SDK retry from double-writing");
    }

    @Test
    void aPinnedKeySendsTheSameOneTwice() {
      FakeSender sender = new FakeSender().then(201, "{}").then(201, "{}");
      String pinned = "11111111-2222-4333-8444-555555555555";
      Chatly pinnedClient = sender.client().withIdempotencyKey(pinned);

      pinnedClient.contacts().upsert(Map.of("externalId", "u-1"));
      pinnedClient.contacts().upsert(Map.of("externalId", "u-1"));

      assertEquals(pinned, sender.idempotencyKeyOf(0));
      assertEquals(pinned, sender.idempotencyKeyOf(1));
    }

    /**
     * Every namespace on a derived client must dispatch through the derived transport.
     *
     * <p>The Node SDK's equivalent rebound four of five namespaces and forgot {@code search}, so a
     * pinned client's search calls went through the parent client. It was invisible because search
     * is read-only. This walks all twelve namespaces plus the escape hatch and checks the pinned key
     * is carried, which is only true if the namespace is bound to the derived transport.
     */
    @Test
    void everyNamespaceOnADerivedClientUsesTheDerivedTransport() {
      String pinned = "99999999-8888-4777-8666-555555555555";
      FakeSender sender = new FakeSender();
      for (int i = 0; i < 10; i++) {
        sender.then(200, "{}");
      }
      Chatly pinnedClient = sender.client().withIdempotencyKey(pinned);

      pinnedClient.contacts().upsert(Map.of());
      pinnedClient.conversations().send("cv_1", "hi");
      pinnedClient.tickets().bulk(List.of("t_1"), "close");
      pinnedClient.macros().create(Map.of("name", "n", "scope", "workspace", "body", "b"));
      pinnedClient.events().track("c_1", "did.thing");
      pinnedClient.webhooks().redeliver("d_1");
      pinnedClient.contacts().addTags("c_1", "vip");
      pinnedClient.contacts().merge("c_1", "c_2");
      pinnedClient.tickets().split("t_1", "a title");
      pinnedClient.request("POST", "/v1/anything", null, Map.of());

      assertEquals(10, sender.callCount());
      for (int i = 0; i < 10; i++) {
        assertEquals(
            pinned,
            sender.idempotencyKeyOf(i),
            "request " + i + " (" + sender.requests.get(i).url() + ") lost the pinned key");
      }
    }

    @Test
    void pinningDoesNotMutateTheOriginalClient() {
      FakeSender sender = new FakeSender().then(201, "{}").then(201, "{}");
      Chatly original = sender.client();
      Chatly derived = original.withIdempotencyKey("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

      derived.contacts().upsert(Map.of());
      original.contacts().upsert(Map.of());

      assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", sender.idempotencyKeyOf(0));
      assertNotEquals(
          "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
          sender.idempotencyKeyOf(1),
          "the original client must still mint fresh keys");
    }

    @Test
    void aKeyShorterThanTheApiAcceptsIsRejectedLocally() {
      FakeSender sender = new FakeSender();
      assertThrows(
          IllegalArgumentException.class, () -> sender.client().withIdempotencyKey("too-short"));
    }

    @Test
    void namespaceAccessorsAreStable() {
      // Cheap, but it catches a namespace accidentally constructed per call,
      // which would make `withIdempotencyKey` look fine and still be wrong.
      Chatly chatly = new FakeSender().client();
      assertSame(chatly.contacts(), chatly.contacts());
      assertSame(chatly.workspace(), chatly.workspace());
    }
  }


  @Nested
  @DisplayName("§10.5 retries: safe things only")
  class Retries {

    @Test
    void a429IsRetriedAndThenSucceeds() {
      FakeSender sender =
          new FakeSender()
              .then(429, FakeSender.errorBody("rate_limited", "slow down"))
              .then(200, "{\"workspaceId\":\"ws_1\",\"scopes\":[]}");

      Map<String, Object> me = sender.client().whoami();

      assertEquals(2, sender.callCount(), "the 429 should have been retried exactly once");
      assertEquals("ws_1", me.get("workspaceId"));
    }

    @Test
    void a500IsRetriedAndThenSucceeds() {
      FakeSender sender =
          new FakeSender().then(500, FakeSender.errorBody("unknown", "boom")).then(200, "{}");
      sender.client().whoami();
      assertEquals(2, sender.callCount());
    }

    @Test
    void aConnectionFailureIsRetried() {
      FakeSender sender = new FakeSender().thenIoError("connect timed out").then(200, "{}");
      sender.client().whoami();
      assertEquals(2, sender.callCount());
    }

    @Test
    void a400IsNotRetried() {
      FakeSender sender = new FakeSender().then(400, FakeSender.errorBody("validation_failed", "no"));

      assertThrows(ChatlyException.class, () -> sender.client().whoami());

      assertEquals(1, sender.callCount(), "a 400 is a deterministic refusal; retrying it is waste");
    }

    @Test
    void a404AndA422AreNotRetried() {
      FakeSender notFound = new FakeSender().then(404, FakeSender.errorBody("not_found", "no"));
      assertThrows(NotFoundException.class, () -> notFound.client().whoami());
      assertEquals(1, notFound.callCount());

      FakeSender invalid =
          new FakeSender().then(422, FakeSender.errorBody("validation_failed", "no"));
      assertThrows(ValidationException.class, () -> invalid.client().whoami());
      assertEquals(1, invalid.callCount());
    }

    @Test
    void threeAttemptsByDefaultThenTheErrorIsThrown() {
      FakeSender sender =
          new FakeSender()
              .then(500, FakeSender.errorBody("unknown", "1"))
              .then(500, FakeSender.errorBody("unknown", "2"))
              .then(500, FakeSender.errorBody("unknown", "3"));

      ServerException e = assertThrows(ServerException.class, () -> sender.client().whoami());

      assertEquals(3, sender.callCount(), "3 attempts total = 1 try + 2 retries");
      assertEquals("3", e.getMessage(), "the LAST failure is what surfaces");
    }

    /**
     * The point of the {@code Idempotency-Key} header.
     *
     * <p>A retried write must reuse the key of the attempt that failed. Minting one per attempt —
     * which is what happens if the mint lands inside the retry loop instead of before it — turns one
     * retried write into three distinct operations upstream, and the customer gets three contacts or
     * three messages.
     */
    @Test
    void aRetriedWriteReusesTheSameIdempotencyKey() {
      FakeSender sender =
          new FakeSender()
              .then(500, FakeSender.errorBody("unknown", "boom"))
              .then(429, FakeSender.errorBody("rate_limited", "slow"))
              .then(201, "{\"id\":\"c_1\"}");

      sender.client().contacts().upsert(Map.of("externalId", "u-1"));

      assertEquals(3, sender.callCount());
      assertEquals(sender.idempotencyKeyOf(0), sender.idempotencyKeyOf(1));
      assertEquals(sender.idempotencyKeyOf(1), sender.idempotencyKeyOf(2));
    }

    @Test
    void aRetriedWriteResendsTheSameBody() {
      FakeSender sender =
          new FakeSender().then(500, FakeSender.errorBody("unknown", "boom")).then(201, "{}");
      sender.client().conversations().send("cv_1", "hello");

      assertEquals(sender.bodies.get(0), sender.bodies.get(1));
      assertEquals("{\"body\":\"hello\"}", sender.bodies.get(1));
    }

    @Test
    void aRetryAfterLongerThanWeWillBlockForStopsTheRetry() {
      FakeSender sender =
          new FakeSender()
              .then(
                  429,
                  FakeSender.errorBody("rate_limited", "come back in an hour"),
                  Map.of("retry-after", List.of("3600")));

      RateLimitException e = assertThrows(RateLimitException.class, () -> sender.client().whoami());

      assertEquals(1, sender.callCount(), "a library may not block a caller's thread for an hour");
      assertEquals(
          Duration.ofSeconds(3600),
          e.retryAfter().orElseThrow(),
          "the server's advice must still reach the caller intact");
    }

    @Test
    void retriesCanBeTurnedOff() {
      FakeSender sender = new FakeSender().then(500, FakeSender.errorBody("unknown", "boom"));
      assertThrows(ServerException.class, () -> sender.clientNoRetries().whoami());
      assertEquals(1, sender.callCount());
    }
  }

  @Nested
  @DisplayName("request shaping")
  class RequestShaping {

    @Test
    void repeatedQueryKeysStayRepeated() {
      FakeSender sender = new FakeSender().then(200, "{\"items\":[],\"nextCursor\":null}");
      sender
          .client()
          .search()
          .query("refund", Query.of().add("kinds", "kb").add("kinds", "ticket"));

      String url = sender.lastRequest().url();
      assertTrue(url.contains("kinds=kb"), url);
      assertTrue(url.contains("kinds=ticket"), url);
      assertFalse(url.contains("kb%2Carticle"), "comma-joining asks for one kind named 'kb,article'");
    }

    @Test
    void pathSegmentsArePercentEncoded() {
      FakeSender sender = new FakeSender().then(200, "{}");
      sender.client().contacts().removeTag("c_1", "needs follow up");

      assertEquals(
          "https://api.chatlychat.com/v1/contacts/c_1/tags/needs%20follow%20up",
          sender.lastRequest().url(),
          "a space in a path must be %20, never '+'");
    }

    @Test
    void aPathSegmentCannotEscapeIntoTheRoute() {
      FakeSender sender = new FakeSender().then(200, "{}");
      sender.client().contacts().removeTag("c_1", "a/../../admin");

      assertTrue(
          sender.lastRequest().url().endsWith("/tags/a%2F..%2F..%2Fadmin"),
          sender.lastRequest().url());
    }

    @Test
    void escapeHatchGoesThroughTheSameAuthAndIdempotency() {
      FakeSender sender = new FakeSender().then(200, "{\"ok\":true}");
      Object out =
          sender
              .client()
              .request("POST", "/v1/some/unlisted/route", Query.of().add("x", "1"), Map.of("a", 1));

      Transport.Request req = sender.lastRequest();
      assertEquals("POST", req.method());
      assertEquals("https://api.chatlychat.com/v1/some/unlisted/route?x=1", req.url());
      assertEquals("Bearer ck_test_key", req.header("authorization").orElseThrow());
      assertTrue(req.header("Idempotency-Key").isPresent());
      assertEquals("{\"a\":1}", sender.bodies.get(0));
      assertEquals(Map.of("ok", true), out);
    }

    @Test
    void escapeHatchRejectsARelativePathAndAnUnknownVerb() {
      Chatly chatly = new FakeSender().client();
      assertThrows(IllegalArgumentException.class, () -> chatly.request("GET", "v1/contacts"));
      assertThrows(IllegalArgumentException.class, () -> chatly.request("TRACE", "/v1/contacts"));
    }

    @Test
    void a204ReturnsNullRatherThanFailingToParseAnEmptyBody() {
      FakeSender sender = new FakeSender().then(204, "");
      sender.client().macros().delete("m_1");
      assertEquals("DELETE", sender.lastRequest().method());
    }

    @Test
    void requiredArgumentsAreCheckedBeforeARequestIsMade() {
      FakeSender sender = new FakeSender();
      Chatly chatly = sender.client();

      assertThrows(IllegalArgumentException.class, () -> chatly.contacts().get(""));
      assertThrows(IllegalArgumentException.class, () -> chatly.search().query(""));
      assertThrows(IllegalArgumentException.class, () -> chatly.billing().usage(null));
      assertThrows(IllegalArgumentException.class, () -> chatly.contacts().bulkUpsert(List.of()));
      assertThrows(
          IllegalArgumentException.class, () -> chatly.search().query("x".repeat(257)));

      assertEquals(0, sender.callCount(), "none of those should have reached the network");
    }

    @Test
    void anArrayRouteDecodesToAList() {
      FakeSender sender = new FakeSender().then(200, "[{\"id\":\"l_1\"},{\"id\":\"l_2\"}]");
      List<Map<String, Object>> labels = sender.client().workspace().labels();
      assertEquals(2, labels.size());
      assertEquals("l_2", labels.get(1).get("id"));
    }

    @Test
    void anObjectWhereAnArrayWasPromisedIsANamedFailureNotAClassCastException() {
      FakeSender sender = new FakeSender().then(200, "{\"items\":[]}");
      ChatlyException e =
          assertThrows(ChatlyException.class, () -> sender.client().workspace().labels());
      assertTrue(e.getMessage().contains("/v1/labels"), e.getMessage());
      assertTrue(e.getMessage().contains("expected a JSON array"), e.getMessage());
    }
  }
}
