package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bots.rotate_external_secret} — asserted on what the SDK SENT.
 *
 * <p>The server's body schema is {@code .strict()}: a snake_case {@code activate_at}, or any key
 * beside {@code activateAt}, is a 400. And before d509799a the handler had no body at all, so a
 * misspelled field was silently discarded with a 201 — nothing in a response would show it. The
 * response fixtures below only matter for the last two tests; the rest read the recorded request.
 */
@DisplayName("bots.rotateExternalSecret sends activateAt exactly, and nothing new without it")
class RotateExternalSecretRequestTest {

  private static final String URL =
      Chatly.DEFAULT_BASE_URL + "/v1/bots/bot_1/external/rotate-secret";
  private static final String DEFERRED =
      "{\"signingSecret\":\"whsec_x\",\"activatesAt\":\"2026-10-16T09:00:00.000Z\"}";
  private static final String HARD_CUT = "{\"signingSecret\":\"whsec_x\",\"activatesAt\":null}";

  private static String bodyOf(Transport.Request request) {
    return request.body() == null ? null : new String(request.body(), StandardCharsets.UTF_8);
  }

  @Test
  void withActivateAtItIsAKeyedPostWhoseBodyIsExactlyTheCamelCaseKey() {
    FakeSender sender = new FakeSender().then(201, DEFERRED);
    sender.client().bots().rotateExternalSecret("bot_1", Instant.parse("2026-10-16T09:00:00Z"));

    assertEquals(1, sender.callCount());
    Transport.Request request = sender.lastRequest();
    assertEquals("POST", request.method());
    assertEquals(URL, request.url());
    String key = request.header("Idempotency-Key").orElse(null);
    assertTrue(key != null && key.matches("[0-9a-fA-F-]{36}"), "Idempotency-Key, got " + key);
    assertEquals(List.of("application/json"), request.headers().get("Content-Type"));

    String raw = bodyOf(request);
    assertEquals(Map.of("activateAt", "2026-10-16T09:00:00Z"), Json.parse(raw), raw);
    assertFalse(raw.contains("activate_at"), "the server's strict schema 400s snake_case: " + raw);
    assertEquals("{\"activateAt\":\"2026-10-16T09:00:00Z\"}", raw);
  }

  @Test
  void aNonUtcInstantIsSentAsTheSameMomentInUtc() {
    // 11:30 at +02:00 is 09:30Z. Sending "11:30Z" would move the switch two hours.
    Instant at = OffsetDateTime.parse("2026-10-16T11:30:15.250+02:00").toInstant();
    FakeSender sender = new FakeSender().then(201, DEFERRED);
    sender.client().bots().rotateExternalSecret("bot_1", at);

    Object sent = ((Map<?, ?>) Json.parse(bodyOf(sender.lastRequest()))).get("activateAt");
    assertEquals("2026-10-16T09:30:15.250Z", sent);
    assertEquals(at, Instant.parse((String) sent));
  }

  @Test
  void withoutActivateAtTheRequestIsWhatItWasBeforeTheOverloadExisted() {
    // Captured from the one-arg method before the overload was added: POST, these five
    // headers, body exactly `{}` with a JSON Content-Type.
    FakeSender sender = new FakeSender().then(201, HARD_CUT).then(201, HARD_CUT);
    sender.client().bots().rotateExternalSecret("bot_1");
    sender.client().bots().rotateExternalSecret("bot_1", null);

    for (Transport.Request request : sender.requests) {
      assertEquals("POST", request.method());
      assertEquals(URL, request.url());
      assertEquals("{}", bodyOf(request));
      assertEquals(
          Set.of("Authorization", "User-Agent", "Accept", "Idempotency-Key", "Content-Type"),
          request.headers().keySet());
      assertEquals(List.of("application/json"), request.headers().get("Content-Type"));
    }
  }

  @Test
  void theCallerCanReadActivatesAtWhenTheSwitchIsDeferred() {
    FakeSender sender = new FakeSender().then(201, DEFERRED);
    Map<String, Object> out =
        sender.client().bots().rotateExternalSecret("bot_1", Instant.parse("2026-10-16T09:00:00Z"));
    assertEquals("whsec_x", out.get("signingSecret"));
    assertEquals("2026-10-16T09:00:00.000Z", out.get("activatesAt"));
  }

  @Test
  void activatesAtIsPresentAndNullOnAHardCut() {
    FakeSender sender = new FakeSender().then(201, HARD_CUT);
    Map<String, Object> out = sender.client().bots().rotateExternalSecret("bot_1");
    assertEquals("whsec_x", out.get("signingSecret"));
    assertTrue(out.containsKey("activatesAt"));
    assertNull(out.get("activatesAt"));
  }
}
