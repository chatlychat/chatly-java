package com.chatlychat.chatly;

import com.chatlychat.chatly.errors.ChatlyException;
import com.chatlychat.chatly.errors.NotFoundException;
import com.chatlychat.chatly.errors.RateLimitException;
import com.chatlychat.chatly.errors.ServerException;
import com.chatlychat.chatly.errors.TransportException;
import com.chatlychat.chatly.errors.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The same nine contract assertions as the JUnit suite, with no test framework at all.
 *
 * <p><strong>Why this exists.</strong> {@code mvn test} needs JUnit from Maven Central the first
 * time it runs. On a machine with no network and a cold {@code ~/.m2}, that is not a slow build, it
 * is no build — and "the tests pass" would then be a claim nobody could check. This class makes the
 * suite verifiable with nothing but a JDK:
 *
 * <pre>{@code
 * javac -d target/verify -cp target/classes \
 *     src/test/java/com/chatlychat/chatly/FakeSender.java \
 *     src/test/java/com/chatlychat/chatly/Verify.java
 * java -cp target/classes:target/verify com.chatlychat.chatly.Verify
 * }</pre>
 *
 * <p>It is a <em>supplement</em>, not a replacement: {@code mvn test} runs 142 assertions across
 * seven classes, this runs the nine the contract names plus a handful that guard them. If the two
 * ever disagree, the JUnit suite is the one to trust — it is bigger. Both read the same pinned
 * vectors.
 *
 * <p>Exits 0 when everything passes, 1 otherwise, with a count either way.
 */
public final class Verify {

  private static int passed;
  private static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) {
    section("§10.1  default base URL is the production host");
    defaultBaseUrlIsProduction();

    section("§10.2  Authorization carries the key");
    authorizationCarriesTheKey();

    section("§10.3  idempotency keys: present, fresh per call, pinnable");
    idempotencyKeys();

    section("§10.4  typed errors with request_id");
    typedErrors();

    section("§10.5  a 429 is retried, a 400 is not");
    retries();

    section("§10.6  the auto-paginator walks two pages and stops");
    pagination();

    section("§10.7  webhook verification refuses, and never throws");
    webhookRefusals();

    section("§10.8  identity_hash pinned vector");
    identityVector();

    section("§10.9  webhook pinned delivery");
    webhookPinnedDelivery();

    section("extra    JSON codec, and construction guards");
    extras();

    System.out.println();
    if (failures.isEmpty()) {
      System.out.println("PASS  " + passed + " assertions, 0 failures");
      System.exit(0);
    }
    System.out.println("FAIL  " + passed + " passed, " + failures.size() + " failed:");
    for (String f : failures) {
      System.out.println("  - " + f);
    }
    System.exit(1);
  }

  // ------------------------------------------------------------- the checks

  private static void defaultBaseUrlIsProduction() {
    eq("https://api.chatlychat.com", Chatly.DEFAULT_BASE_URL, "DEFAULT_BASE_URL constant");

    FakeSender sender = new FakeSender().then(200, "{\"workspaceId\":\"ws_1\"}");
    Chatly.builder().apiKey("ck_test_key").sender(sender).build().whoami();
    eq(
        "https://api.chatlychat.com/v1/api-keys/whoami",
        sender.lastRequest().url(),
        "a default-built client calls the production host");
  }

  private static void authorizationCarriesTheKey() {
    FakeSender sender = new FakeSender().then(200, "{}");
    sender.client().whoami();
    Transport.Request req = sender.lastRequest();
    eq("Bearer ck_test_key", req.header("authorization").orElse(null), "Authorization header");
    eq("application/json", req.header("accept").orElse(null), "Accept header");
    eq("chatly-sdk-java/0.1.0", req.header("user-agent").orElse(null), "User-Agent header");
  }

  private static void idempotencyKeys() {
    FakeSender sender = new FakeSender().then(201, "{}").then(201, "{}");
    Chatly chatly = sender.client();
    chatly.contacts().upsert(Map.of("externalId", "u-1"));
    chatly.contacts().upsert(Map.of("externalId", "u-2"));

    String first = sender.idempotencyKeyOf(0);
    String second = sender.idempotencyKeyOf(1);
    truth(first != null && first.length() >= 16, "a write carries an Idempotency-Key");
    truth(!first.equals(second), "two writes carry different keys");

    FakeSender pinnedSender = new FakeSender().then(201, "{}").then(201, "{}");
    String pin = "11111111-2222-4333-8444-555555555555";
    Chatly pinned = pinnedSender.client().withIdempotencyKey(pin);
    pinned.contacts().upsert(Map.of());
    pinned.contacts().upsert(Map.of());
    eq(pin, pinnedSender.idempotencyKeyOf(0), "a pinned key is sent");
    eq(pin, pinnedSender.idempotencyKeyOf(1), "a pinned key is sent again, unchanged");

    FakeSender readSender = new FakeSender().then(200, "{}");
    readSender.client().whoami();
    truth(
        readSender.lastRequest().header("Idempotency-Key").isEmpty(),
        "a read sends no Idempotency-Key");
  }

  private static void typedErrors() {
    Map<String, List<String>> withId = Map.of("x-request-id", List.of("req_abc123"));

    ChatlyException notFound =
        catching(
            () ->
                new FakeSender()
                    .then(404, FakeSender.errorBody("not_found", "No such contact"), withId)
                    .clientNoRetries()
                    .contacts()
                    .get("c_missing"));
    truth(notFound instanceof NotFoundException, "404 -> NotFoundException");
    eq(404, notFound.status(), "404 status");
    eq("not_found", notFound.code().orElse(null), "404 code");
    eq("req_abc123", notFound.requestId().orElse(null), "404 request_id");

    ChatlyException invalid =
        catching(
            () ->
                new FakeSender()
                    .then(422, FakeSender.errorBody("validation_failed", "bad"), withId)
                    .clientNoRetries()
                    .whoami());
    truth(invalid instanceof ValidationException, "422 -> ValidationException");
    eq("req_abc123", invalid.requestId().orElse(null), "422 request_id");

    ChatlyException limited =
        catching(
            () ->
                new FakeSender()
                    .then(
                        429,
                        FakeSender.errorBody("rate_limited", "slow"),
                        Map.of(
                            "retry-after", List.of("30"),
                            "x-request-id", List.of("req_abc123")))
                    .clientNoRetries()
                    .whoami());
    truth(limited instanceof RateLimitException, "429 -> RateLimitException");
    eq(
        Duration.ofSeconds(30),
        ((RateLimitException) limited).retryAfter().orElse(null),
        "429 retry_after");
    eq("req_abc123", limited.requestId().orElse(null), "429 request_id");

    ChatlyException transport =
        catching(() -> new FakeSender().thenIoError("refused").clientNoRetries().whoami());
    truth(transport instanceof TransportException, "a connection failure is its own type");
    eq(0, transport.status(), "a transport failure has status 0");
  }

  private static void retries() {
    FakeSender retried =
        new FakeSender()
            .then(429, FakeSender.errorBody("rate_limited", "slow"))
            .then(200, "{\"workspaceId\":\"ws_1\"}");
    Map<String, Object> me = retried.client().whoami();
    eq(2, retried.callCount(), "a 429 is retried once");
    eq("ws_1", me.get("workspaceId"), "and then succeeds");

    FakeSender notRetried = new FakeSender().then(400, FakeSender.errorBody("validation_failed", "no"));
    catching(() -> notRetried.client().whoami());
    eq(1, notRetried.callCount(), "a 400 is NOT retried");

    FakeSender writeRetry =
        new FakeSender()
            .then(500, FakeSender.errorBody("unknown", "boom"))
            .then(201, "{\"id\":\"c_1\"}");
    writeRetry.client().contacts().upsert(Map.of("externalId", "u-1"));
    eq(
        writeRetry.idempotencyKeyOf(0),
        writeRetry.idempotencyKeyOf(1),
        "a retried write reuses its Idempotency-Key");

    FakeSender exhausted =
        new FakeSender()
            .then(500, FakeSender.errorBody("unknown", "1"))
            .then(500, FakeSender.errorBody("unknown", "2"))
            .then(500, FakeSender.errorBody("unknown", "3"));
    ChatlyException e = catching(() -> exhausted.client().whoami());
    truth(e instanceof ServerException, "an exhausted retry surfaces the server error");
    eq(3, exhausted.callCount(), "3 attempts by default");
  }

  private static void pagination() {
    FakeSender sender =
        new FakeSender()
            .then(200, "{\"items\":[{\"id\":\"c_1\"},{\"id\":\"c_2\"}],\"nextCursor\":\"cur_2\"}")
            .then(200, "{\"items\":[{\"id\":\"c_3\"}],\"nextCursor\":null}");

    List<String> ids = new ArrayList<>();
    for (Map<String, Object> row : sender.client().contacts().listAuto()) {
      ids.add((String) row.get("id"));
    }
    eq(List.of("c_1", "c_2", "c_3"), ids, "the paginator yields every row in order");
    eq(2, sender.callCount(), "it stops on a null cursor, without asking for a third page");
    truth(sender.requests.get(1).url().contains("cursor=cur_2"), "page two sends the cursor");

    FakeSender streamed =
        new FakeSender()
            .then(200, "{\"items\":[{\"id\":\"c_1\"}],\"nextCursor\":\"cur_2\"}")
            .then(200, "{\"items\":[{\"id\":\"c_2\"}],\"nextCursor\":null}");
    eq(
        List.of("c_1", "c_2"),
        streamed.client().contacts().listAuto().stream().map(r -> (String) r.get("id")).toList(),
        "the Stream view sees the same rows");

    FakeSender looping = new FakeSender();
    for (int i = 0; i < 6; i++) {
      looping.then(200, "{\"items\":[{\"id\":\"c\"}],\"nextCursor\":\"cur_loop\"}");
    }
    ChatlyException loop =
        catching(
            () -> {
              for (Map<String, Object> ignored : looping.client().contacts().listAuto()) {
                // drain
              }
            });
    eq(
        "pagination_cursor_repeated",
        loop == null ? null : loop.code().orElse(null),
        "a repeated cursor is refused rather than looped on");
  }

  private static final String PINNED_SECRET = "whsec_test";
  private static final String PINNED_BODY = "{\"id\":\"evt_1\"}";
  private static final long PINNED_T = 1700000000L;
  private static final String PINNED_V1 =
      "c89214b5b5da833daed6f0b8c5bb6bd58cea9022bd80ccc78230f3942d632925";
  private static final String BODY_ALONE_V1 =
      "030fa3b2413d1993c551364bd53bb9b3edb5c0c34d55dba6ada6041245632811";

  private static void webhookRefusals() {
    long now = 1760000000L;
    byte[] body = "{\"id\":\"evt_live\"}".getBytes(StandardCharsets.UTF_8);
    String genuine = Webhooks.signatureHeaderFor(body, "whsec_live", now);

    truth(
        Webhooks.verifySignature(body, genuine, "whsec_live", Duration.ofSeconds(300), now),
        "a genuine fresh delivery is accepted");
    truth(
        !Webhooks.verifySignature(
            body, genuine, "whsec_live", Duration.ofSeconds(300), now + 301),
        "a stale timestamp is refused");

    String[] malformed = {
      "", "garbage", "t=1700000000", "v1=" + PINNED_V1, "t=notanumber,v1=" + PINNED_V1,
      "t=1700000000,v1=", "t=1700000000,v1=zzzz", "t=1700000000;v1=" + PINNED_V1, ",,,",
    };
    boolean anyThrew = false;
    boolean anyAccepted = false;
    for (String header : malformed) {
      try {
        if (Webhooks.verifySignature(body, header, "whsec_live", Duration.ofSeconds(300), now)) {
          anyAccepted = true;
        }
      } catch (RuntimeException thrown) {
        anyThrew = true;
      }
    }
    truth(!anyThrew, "no malformed header causes a throw");
    truth(!anyAccepted, "no malformed header is accepted");
  }

  private static void identityVector() {
    eq(
        "5e26772dce97d5ba995796e621f83f2e97e6f8c3f9386dbf153a98ab24c10271",
        Identity.hash("sek_test_secret", "user_42"),
        "identity_hash(\"sek_test_secret\", \"user_42\")");

    boolean threw = false;
    try {
      Identity.hash("", "user_42");
    } catch (IllegalArgumentException expected) {
      threw = true;
    }
    truth(threw, "an empty identity secret is refused");
  }

  private static void webhookPinnedDelivery() {
    byte[] body = PINNED_BODY.getBytes(StandardCharsets.UTF_8);
    eq(14, body.length, "the pinned body is 14 bytes with no trailing newline");

    truth(
        Webhooks.verifySignature(
            body, "t=" + PINNED_T + ",v1=" + PINNED_V1, PINNED_SECRET,
            Duration.ofSeconds(300), PINNED_T),
        "the pinned delivery verifies");
    truth(
        !Webhooks.verifySignature(
            body, "t=" + PINNED_T + ",v1=" + BODY_ALONE_V1, PINNED_SECRET,
            Duration.ofSeconds(300), PINNED_T),
        "the same delivery signed over the body ALONE is refused");
    eq(
        "t=" + PINNED_T + ",v1=" + PINNED_V1,
        Webhooks.signatureHeaderFor(body, PINNED_SECRET, PINNED_T),
        "the signed payload is \"<t>.\" + body");
  }

  private static void extras() {
    eq("{\"a\":1,\"b\":[true,null]}", Json.write(ordered()), "JSON write");
    eq(Map.of("a", 1L), Json.parse("{\"a\":1}"), "JSON parse");
    eq("🙂", Json.parse("\"\\ud83d\\ude42\""), "a surrogate pair is joined");

    boolean rejectedTrailing = false;
    try {
      Json.parse("{\"a\":1} trailing");
    } catch (Json.JsonParseException expected) {
      rejectedTrailing = true;
    }
    truth(rejectedTrailing, "trailing content after a document is refused");

    boolean rejectedEmptyKey = false;
    try {
      Chatly.builder().apiKey("").build();
    } catch (IllegalArgumentException expected) {
      rejectedEmptyKey = true;
    }
    truth(rejectedEmptyKey, "an empty API key is refused at construction");

    eq(
        "kinds=kb&kinds=ticket",
        Query.of().add("kinds", "kb").add("kinds", "ticket").encode(),
        "repeated query keys stay repeated");
    eq(
        "needs%20follow%20up",
        Query.encodePathSegment("needs follow up"),
        "a path segment is RFC 3986 encoded, not form encoded");
  }

  private static Map<String, Object> ordered() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("a", 1);
    m.put("b", listWithNull());
    return m;
  }

  /**
   * {@code List.of} rejects a null element, and the codec must be able to write one -- so this
   * builds the list the long way. (The first draft of this file used {@code List.of(true, null)}
   * and died with a NullPointerException inside the assertion rather than inside the codec.)
   */
  private static List<Object> listWithNull() {
    List<Object> l = new ArrayList<>();
    l.add(Boolean.TRUE);
    l.add(null);
    return l;
  }

  // ------------------------------------------------------------- assertions

  private static void section(String title) {
    System.out.println(title);
  }

  private static void eq(Object expected, Object actual, String what) {
    boolean ok = expected == null ? actual == null : expected.equals(actual);
    report(ok, what, ok ? null : "expected <" + expected + "> but was <" + actual + ">");
  }

  private static void truth(boolean condition, String what) {
    report(condition, what, condition ? null : "was false");
  }

  private static void report(boolean ok, String what, String detail) {
    if (ok) {
      passed++;
      System.out.println("  ok    " + what);
    } else {
      failures.add(what + " -- " + detail);
      System.out.println("  FAIL  " + what + " -- " + detail);
    }
  }

  /** Run something that should throw a {@link ChatlyException} and hand it back. */
  private static ChatlyException catching(Runnable action) {
    try {
      action.run();
      failures.add("expected a ChatlyException, nothing was thrown");
      System.out.println("  FAIL  expected a ChatlyException, nothing was thrown");
      return null;
    } catch (ChatlyException e) {
      return e;
    }
  }

  private Verify() {}
}
