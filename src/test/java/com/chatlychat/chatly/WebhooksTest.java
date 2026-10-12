package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CONTRACT.md §10.7 and §10.9 — webhook signature verification, including the pinned delivery. */
class WebhooksTest {

  // ---------------------------------------------------------------- §10.9

  /**
   * The pinned delivery from CONTRACT.md §10.9, verbatim.
   *
   * <p>Computed and cross-checked in more than one implementation. If this SDK disagrees with it,
   * this SDK is wrong.
   */
  private static final String PINNED_SECRET = "whsec_test";

  private static final String PINNED_BODY = "{\"id\":\"evt_1\"}";

  private static final long PINNED_T = 1700000000L;

  private static final String PINNED_V1 =
      "c89214b5b5da833daed6f0b8c5bb6bd58cea9022bd80ccc78230f3942d632925";

  private static final String PINNED_HEADER = "t=" + PINNED_T + ",v1=" + PINNED_V1;

  /** The same body signed WITHOUT the timestamp — the construction the Node SDK once used. */
  private static final String BODY_ALONE_V1 =
      "030fa3b2413d1993c551364bd53bb9b3edb5c0c34d55dba6ada6041245632811";

  @Test
  @DisplayName("§10.9 accepts the pinned delivery exactly as specified")
  void acceptsThePinnedDelivery() {
    assertEquals(14, PINNED_BODY.getBytes(StandardCharsets.UTF_8).length, "14 bytes, no newline");

    assertTrue(
        Webhooks.verifySignature(
            PINNED_BODY.getBytes(StandardCharsets.UTF_8),
            PINNED_HEADER,
            PINNED_SECRET,
            Duration.ofSeconds(300),
            PINNED_T),
        "the pinned 2023 delivery must verify against a fixed now of " + PINNED_T);
  }

  @Test
  @DisplayName("§10.9 accepts it with tolerance disabled, as an archived delivery")
  void acceptsThePinnedDeliveryWithNoTolerance() {
    assertTrue(
        Webhooks.verifySignature(
            PINNED_BODY.getBytes(StandardCharsets.UTF_8),
            PINNED_HEADER,
            PINNED_SECRET,
            null,
            System.currentTimeMillis() / 1000));
  }

  @Test
  @DisplayName("§10.9 rejects the same delivery signed over the body alone")
  void rejectsADigestOverTheBodyAlone() {
    // This is the bug that shipped: HMAC over the body with the timestamp left
    // out, which rejected 100% of genuine deliveries. A verifier that accepts
    // THIS would accept a replay of any genuine body under a fresh timestamp.
    String forged = "t=" + PINNED_T + ",v1=" + BODY_ALONE_V1;

    assertFalse(
        Webhooks.verifySignature(
            PINNED_BODY.getBytes(StandardCharsets.UTF_8),
            forged,
            PINNED_SECRET,
            Duration.ofSeconds(300),
            PINNED_T));
  }

  /**
   * Belt and braces on the construction itself.
   *
   * <p>Asserting that the right header is accepted and one wrong header is rejected still leaves
   * room for a verifier that signs, say, {@code t + body} with no separator. This pins the exact
   * signed payload: {@code "<t>" + "." + body}.
   */
  @Test
  void theSignedPayloadIsTimestampDotBody() {
    String generated =
        Webhooks.signatureHeaderFor(
            PINNED_BODY.getBytes(StandardCharsets.UTF_8), PINNED_SECRET, PINNED_T);
    assertEquals(PINNED_HEADER, generated);
  }

  // ---------------------------------------------------------------- §10.7

  @Test
  @DisplayName("§10.7 accepts a genuine, fresh delivery")
  void acceptsAGenuineFreshDelivery() {
    long now = 1760000000L;
    byte[] body = "{\"id\":\"evt_live\",\"type\":\"conversation.created\"}".getBytes(StandardCharsets.UTF_8);
    String header = Webhooks.signatureHeaderFor(body, "whsec_live", now);

    assertTrue(Webhooks.verifySignature(body, header, "whsec_live", Duration.ofSeconds(300), now));
    assertTrue(
        Webhooks.verifySignature(body, header, "whsec_live", Duration.ofSeconds(300), now + 299),
        "inside the window");
  }

  @Test
  @DisplayName("§10.7 rejects a stale timestamp")
  void rejectsAStaleTimestamp() {
    long signedAt = 1760000000L;
    byte[] body = "{\"id\":\"evt_old\"}".getBytes(StandardCharsets.UTF_8);
    String header = Webhooks.signatureHeaderFor(body, "whsec_live", signedAt);

    assertFalse(
        Webhooks.verifySignature(
            body, header, "whsec_live", Duration.ofSeconds(300), signedAt + 301),
        "301s old, with a 300s tolerance");
  }

  @Test
  void rejectsATimestampFromTheFutureToo() {
    long signedAt = 1760000000L;
    byte[] body = "{\"id\":\"evt_future\"}".getBytes(StandardCharsets.UTF_8);
    String header = Webhooks.signatureHeaderFor(body, "whsec_live", signedAt);

    // Skew in either direction is outside the window: a clock running fast at
    // the sender is as much a reason to refuse as a replay.
    assertFalse(
        Webhooks.verifySignature(
            body, header, "whsec_live", Duration.ofSeconds(300), signedAt - 301));
  }

  @Test
  @DisplayName("§10.7 rejects a malformed header, and does not throw on any of them")
  void rejectsMalformedHeadersWithoutThrowing() {
    byte[] body = PINNED_BODY.getBytes(StandardCharsets.UTF_8);

    String[] malformed = {
      "",
      "garbage",
      "t=1700000000",
      "v1=" + PINNED_V1,
      "t=,v1=" + PINNED_V1,
      "t=notanumber,v1=" + PINNED_V1,
      "t=1700000000,v1=",
      "t=1700000000,v1=zzzz",
      "t=1700000000,v1=abc",
      "t=1700000000;v1=" + PINNED_V1,
      "t 1700000000,v1 " + PINNED_V1,
      "=1700000000,=" + PINNED_V1,
      "t=1700000000,v2=" + PINNED_V1,
      ",,,",
    };

    for (String header : malformed) {
      boolean result =
          assertDoesNotThrow(
              () ->
                  Webhooks.verifySignature(
                      body, header, PINNED_SECRET, Duration.ofSeconds(300), PINNED_T),
              "a verifier that throws is an ACCEPTED webhook in a handler without a try/catch;"
                  + " header was: " + header);
      assertFalse(result, "should have been refused: " + header);
    }
  }

  @Test
  void rejectsNullsAndAnEmptySecretWithoutThrowing() {
    byte[] body = PINNED_BODY.getBytes(StandardCharsets.UTF_8);

    assertFalse(assertDoesNotThrow(() -> Webhooks.verifySignature(body, null, PINNED_SECRET)));
    assertFalse(assertDoesNotThrow(() -> Webhooks.verifySignature(body, PINNED_HEADER, null)));
    assertFalse(assertDoesNotThrow(() -> Webhooks.verifySignature(body, PINNED_HEADER, "")));
    assertFalse(
        assertDoesNotThrow(
            () -> Webhooks.verifySignature((byte[]) null, PINNED_HEADER, PINNED_SECRET)));
  }

  @Test
  void rejectsTheWrongSecret() {
    assertFalse(
        Webhooks.verifySignature(
            PINNED_BODY.getBytes(StandardCharsets.UTF_8),
            PINNED_HEADER,
            "whsec_not_the_secret",
            Duration.ofSeconds(300),
            PINNED_T));
  }

  @Test
  void rejectsAModifiedBody() {
    assertFalse(
        Webhooks.verifySignature(
            "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8),
            PINNED_HEADER,
            PINNED_SECRET,
            Duration.ofSeconds(300),
            PINNED_T));
  }

  /**
   * Re-serialising a parsed body changes the bytes and so breaks the digest.
   *
   * <p>This is the single most common way a webhook handler fails in the field: a framework binds
   * the body to an object, the handler re-encodes it to verify, and the digest never matches —
   * while the parsed object looks perfectly correct in a debugger. The test exists to document it as
   * expected behaviour rather than a bug to be "fixed" by loosening the check.
   */
  @Test
  void aReserialisedBodyDoesNotVerify() {
    String reserialised = Json.write(Json.parse(PINNED_BODY));
    assertEquals("{\"id\":\"evt_1\"}", reserialised, "same here, by luck of key order");

    String spaced = "{ \"id\": \"evt_1\" }";
    assertFalse(
        Webhooks.verifySignature(
            spaced.getBytes(StandardCharsets.UTF_8),
            PINNED_HEADER,
            PINNED_SECRET,
            Duration.ofSeconds(300),
            PINNED_T),
        "whitespace changes are byte changes");
  }

  @Test
  void aStringOverloadMatchesTheByteOverload() {
    assertTrue(
        Webhooks.verifySignature(
            PINNED_BODY, PINNED_HEADER, PINNED_SECRET, Duration.ofSeconds(300), PINNED_T));
  }

  @Test
  void headerFieldsMayCarrySurroundingWhitespace() {
    String spacedHeader = " t = " + PINNED_T + " , v1 = " + PINNED_V1 + " ";
    assertTrue(
        Webhooks.verifySignature(
            PINNED_BODY.getBytes(StandardCharsets.UTF_8),
            spacedHeader,
            PINNED_SECRET,
            Duration.ofSeconds(300),
            PINNED_T));
  }

  @Test
  void aNonAsciiBodyVerifiesByItsBytes() {
    long now = 1760000000L;
    byte[] body = "{\"body\":\"café ☕\"}".getBytes(StandardCharsets.UTF_8);
    String header = Webhooks.signatureHeaderFor(body, "whsec_live", now);

    assertTrue(Webhooks.verifySignature(body, header, "whsec_live", Duration.ofSeconds(300), now));
  }

  @Test
  void theHeaderNameIsExposedSoAHandlerNeedNotHardcodeIt() {
    assertEquals("x-livechat-signature", Webhooks.SIGNATURE_HEADER);
    assertEquals(Duration.ofSeconds(300), Webhooks.DEFAULT_TOLERANCE);
  }
}
