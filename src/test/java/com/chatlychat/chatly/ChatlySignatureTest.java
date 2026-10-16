package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * {@link Webhooks#verifyChatlySignature} against the cross-SDK corpus, plus the never-throws
 * promise on inputs the corpus cannot express in JSON.
 *
 * <p><strong>The corpus is the contract.</strong> {@code src/test/resources/signature_vectors.json}
 * is a byte-identical copy of the one every backend SDK runs; sdk-node's own spec fails if any copy
 * drifts, so it must never be edited here. It is read off the TEST CLASSPATH rather than a path,
 * because Maven copies {@code src/test/resources} there by default and that is true both in the
 * monorepo and in the public mirror where this directory is the repository root — a relative path
 * to {@code ../sdk-spec} would exist in exactly one of the two.
 */
class ChatlySignatureTest {

  /**
   * A floor, not the count. A loader that silently reads zero vectors runs zero dynamic tests and
   * JUnit reports that as green; the floor is what makes "the corpus passed" mean anything.
   *
   * <p>It is the corpus's CURRENT size, 61. It read 59 — the size when the Go port was written —
   * while the corpus held 61, so a loader that dropped two vectors passed. Raise it when the corpus
   * grows; it may never be below the real count.
   */
  private static final int VECTOR_FLOOR = 61;

  private static List<Object> loadVectors() throws IOException {
    try (InputStream in = ChatlySignatureTest.class.getResourceAsStream("/signature_vectors.json")) {
      assertNotNull(in, "signature_vectors.json is not on the test classpath");
      Map<String, Object> corpus = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      assertEquals(1L, corpus.get("version"), "corpus version this runner understands");
      @SuppressWarnings("unchecked")
      List<Object> vectors = (List<Object>) corpus.get("vectors");
      return vectors;
    }
  }

  @Test
  void theCorpusLoadsAtLeastTheFloor() throws IOException {
    List<Object> vectors = loadVectors();
    assertTrue(
        vectors.size() >= VECTOR_FLOOR,
        "corpus loaded " + vectors.size() + " vectors; floor is " + VECTOR_FLOOR);
  }

  @TestFactory
  Stream<DynamicTest> everyVectorInTheCorpus() throws IOException {
    List<Object> vectors = loadVectors();
    assertTrue(vectors.size() >= VECTOR_FLOOR, "corpus loaded " + vectors.size() + " vectors");
    return vectors.stream()
        .map(
            raw -> {
              @SuppressWarnings("unchecked")
              Map<String, Object> vector = (Map<String, Object>) raw;
              String name = (String) vector.get("name");
              return DynamicTest.dynamicTest(name, () -> runVector(vector));
            });
  }

  @SuppressWarnings("unchecked")
  private static void runVector(Map<String, Object> vector) {
    String name = (String) vector.get("name");
    Map<String, Object> input = (Map<String, Object>) vector.get("input");
    Map<String, Object> expect = (Map<String, Object>) vector.get("expect");

    ChatlySignatureResult r = Webhooks.verifyChatlySignature(fromCorpus(input));

    boolean ok = (Boolean) expect.get("ok");
    assertEquals(ok, r.ok(), name + ": ok — " + r.reasonCode() + " " + r.message());
    assertEquals(expect.get("surface"), r.surface(), name + ": surface");
    if (ok) {
      assertAll(
          name,
          () -> assertEquals(expect.get("base"), r.base().code(), "base"),
          () -> assertEquals(((Long) expect.get("secretIndex")).intValue(), r.secretIndex(), "secretIndex"),
          // `timestampMs: null` in the corpus decodes as Java null; a Long compares by value.
          () -> assertEquals(expect.get("timestampMs"), r.timestampMs(), "timestampMs"),
          () -> assertNull(r.reason(), "no reason on ok"),
          () -> assertNull(r.message(), "no message on ok"));
    } else {
      assertAll(
          name,
          () -> assertEquals(expect.get("reason"), r.reasonCode(), "reason — " + r.message()),
          () -> assertNotNull(r.message(), "a refusal carries a message"),
          () -> assertFalse(r.message().isBlank(), "a refusal's message is not blank"),
          () -> assertNull(r.base(), "no base on a refusal"),
          () -> assertNull(r.secretIndex(), "no secretIndex on a refusal"));
    }
  }

  /** The corpus's JSON input, through the public builder exactly as a caller would use it. */
  @SuppressWarnings("unchecked")
  private static ChatlySignatureInput fromCorpus(Map<String, Object> input) {
    ChatlySignatureInput.Builder b =
        ChatlySignatureInput.builder()
            .surface((String) input.get("surface"))
            .body((String) input.get("body"));

    for (Map.Entry<String, Object> h : ((Map<String, Object>) input.get("headers")).entrySet()) {
      Object value = h.getValue();
      if (value instanceof List) {
        for (Object v : (List<Object>) value) {
          b.header(h.getKey(), (String) v);
        }
      } else {
        b.header(h.getKey(), (String) value);
      }
    }

    Object secret = input.get("secret");
    if (secret instanceof List) {
      List<String> secrets = new ArrayList<>();
      for (Object s : (List<Object>) secret) {
        secrets.add((String) s);
      }
      b.secrets(secrets);
    } else {
      b.secret((String) secret);
    }

    if (input.containsKey("method")) {
      b.method((String) input.get("method"));
    }
    if (input.containsKey("path")) {
      b.path((String) input.get("path"));
    }
    if (input.containsKey("v2")) {
      b.v2(ChatlySignatureInput.V2Policy.valueOf(((String) input.get("v2")).toUpperCase()));
    }
    if (input.containsKey("allowUnprotectedReplay")) {
      b.allowUnprotectedReplay((Boolean) input.get("allowUnprotectedReplay"));
    }
    if (input.containsKey("toleranceSeconds")) {
      b.toleranceSeconds((Long) input.get("toleranceSeconds"));
    }
    b.nowMs((Long) input.get("nowMs"));
    return b.build();
  }

  // ---------------------------------------------------------------- never throws

  /** One genuine request to mutate: event_webhook, signed now. */
  private static final String SECRET = "whsec_vector_secret";

  private static final String BODY = "{\"id\":\"evt_1\"}";

  private static final long NOW_MS = 1_760_000_000_000L;

  private static ChatlySignatureInput.Builder genuine() {
    String header =
        Webhooks.signatureHeaderFor(BODY.getBytes(StandardCharsets.UTF_8), SECRET, NOW_MS / 1000);
    return ChatlySignatureInput.builder()
        .surface(ChatlySignatureSurface.EVENT_WEBHOOK)
        .body(BODY)
        .header("X-Livechat-Signature", header)
        .secret(SECRET)
        .nowMs(NOW_MS);
  }

  @Test
  void theGenuineBaselineVerifies() {
    ChatlySignatureResult r = Webhooks.verifyChatlySignature(genuine().build());
    assertTrue(r.ok(), r.message());
    assertEquals("event_webhook", r.surface());
    assertEquals(ChatlySignatureResult.Base.BODY, r.base());
    assertEquals(0, r.secretIndex());
    assertEquals(NOW_MS, r.timestampMs());
  }

  @Test
  @DisplayName("null body, null headers, null secret, null input: refused, never thrown")
  void garbageIsRefusedNeverThrown() {
    List<ChatlySignatureInput> garbage =
        Arrays.asList(
            null,
            ChatlySignatureInput.builder().build(),
            genuine().body((byte[]) null).build(),
            genuine().body((String) null).build(),
            ChatlySignatureInput.builder()
                .surface(ChatlySignatureSurface.EVENT_WEBHOOK)
                .body(BODY)
                .headers(null)
                .secret(SECRET)
                .build(),
            genuine().secret(null).build(),
            genuine().secrets((List<String>) null).build(),
            genuine().secrets((String[]) null).build(),
            genuine().secrets(Arrays.asList(null, "", null)).build(),
            genuine().surface((String) null).build(),
            genuine().surface((ChatlySignatureSurface) null).build(),
            genuine().surface("EVENT_WEBHOOK").build(),
            genuine().toleranceSeconds(-1).build(),
            genuine().toleranceSeconds(Long.MIN_VALUE).build(),
            genuine().nowMs(Long.MIN_VALUE).build(),
            genuine().nowMs(Long.MAX_VALUE).build());
    for (int i = 0; i < garbage.size(); i++) {
      ChatlySignatureInput in = garbage.get(i);
      ChatlySignatureResult r = assertDoesNotThrow(() -> Webhooks.verifyChatlySignature(in));
      assertFalse(r.ok(), "garbage[" + i + "] should have been refused");
      assertNotNull(r.reason());
      assertFalse(r.message() == null || r.message().isBlank(), "a refusal carries a message");
    }
  }

  @Test
  void aNullBodyIsASignatureMismatchWithItsOwnMessage() {
    ChatlySignatureResult r = Webhooks.verifyChatlySignature(genuine().body((byte[]) null).build());
    assertEquals("signature_mismatch", r.reasonCode());
    assertTrue(r.message().contains("No body"), r.message());
  }

  @Test
  void nullSecretsAreSkippedLikeEmptyOnesAndTheIndexIsTheCallers() {
    ChatlySignatureResult r =
        Webhooks.verifyChatlySignature(genuine().secrets(null, "", "wrong", SECRET).build());
    assertTrue(r.ok(), r.message());
    assertEquals(3, r.secretIndex());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void aHeaderMapWithNonStringsSmuggledThroughARawTypeDoesNotThrow() {
    Map raw = new LinkedHashMap();
    raw.put("x-livechat-signature", Arrays.asList(42, null));
    raw.put(7, List.of("x"));
    raw.put("x-other", "not a collection");
    raw.put(null, null);
    ChatlySignatureResult r =
        assertDoesNotThrow(
            () ->
                Webhooks.verifyChatlySignature(
                    ChatlySignatureInput.builder()
                        .surface("event_webhook")
                        .body(BODY)
                        .headers((Map<String, ? extends Collection<String>>) raw)
                        .secret(SECRET)
                        .nowMs(NOW_MS)
                        .build()));
    assertEquals("missing_signature_header", r.reasonCode());
  }

  @Test
  void aMultimapCollectsAcrossCasingsSoTwoKeysAreADuplicate() {
    String header =
        Webhooks.signatureHeaderFor(BODY.getBytes(StandardCharsets.UTF_8), SECRET, NOW_MS / 1000);
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("x-livechat-signature", List.of(header));
    headers.put("X-LIVECHAT-SIGNATURE", List.of(header));
    ChatlySignatureResult r =
        Webhooks.verifyChatlySignature(
            ChatlySignatureInput.builder()
                .surface("event_webhook")
                .body(BODY)
                .headers(headers)
                .secret(SECRET)
                .nowMs(NOW_MS)
                .build());
    assertEquals("duplicate_signature_header", r.reasonCode());
  }

  @Test
  void aTimestampBeyondALongIsMalformedNotFarFuture() {
    String hex = "0".repeat(64);
    for (String t : new String[] {"99999999999999999999", "9223372036854776"}) {
      // The second fits a long as seconds and overflows only when multiplied to milliseconds.
      ChatlySignatureResult r =
          Webhooks.verifyChatlySignature(
              genuine()
                  .surface("agent_delivery")
                  .header("x-chatly-signature", "t=" + t + ",v1=" + hex)
                  .build());
      assertEquals("malformed_timestamp", r.reasonCode(), t + ": " + r.message());
    }
  }

  @Test
  void hostileSignatureValuesAreRefusedNotThrown() {
    String[] values = {
      "", ",,,", "=", "t=", "v1=", "t=1,v1=" + "z".repeat(64), "sha256=", "t=\u0000,v1=" + "a".repeat(64),
      "t=" + "9".repeat(10_000) + ",v1=" + "a".repeat(64), ",".repeat(100_000),
    };
    for (String v : values) {
      ChatlySignatureResult r =
          assertDoesNotThrow(
              () ->
                  Webhooks.verifyChatlySignature(
                      ChatlySignatureInput.builder()
                          .surface("agent_delivery")
                          .body(BODY)
                          .header("x-chatly-signature", v)
                          .secret(SECRET)
                          .nowMs(NOW_MS)
                          .build()));
      assertFalse(r.ok(), v.length() > 40 ? v.substring(0, 40) : v);
    }
  }

  @Test
  void javascriptTrimNotJavaTrim() {
    // NBSP and BOM are JS whitespace and Java trim() keeps them; a C0 control is the reverse.
    assertEquals("a", ChatlySignatureVerifier.jsTrim(" ﻿ a  "));
    assertEquals("\u0001a", ChatlySignatureVerifier.jsTrim("\u0001a"));
  }

  // -------------------------------------------- the workflow action's dual-digest value

  /*
   * The one construction a real sender emits that the shared corpus does not hold.
   *
   * `WORKFLOW_WEBHOOK_V2_PHASE` in apps/worker/src/handlers/action-handlers/fire-webhook.ts is
   * `'dual'` today, which puts TWO digests in one value and NO marker:
   *
   *     x-chatly-signature-v2: t=<T>,v1=<HMAC(<T>.<METHOD>.<path>.<body>)>,v1=<HMAC(<T>.<body>)>
   *
   * so a receiver that passes method and path gets the unified base and one that does not still
   * verifies, with neither side changing on the day the sender drops to `'target'`. Three things
   * have to hold for that to be true, and each is one test below: the target digest wins when a
   * target is available, the body digest carries a receiver that has none, and `REQUIRE` binds the
   * path rather than silently taking the weaker digest that is sitting right beside it.
   *
   * The two digests are PINNED from the reference implementation
   * (`packages/sdk-node/src/signatures.ts`), not computed here: a test that mints with the same
   * base-string code it is testing agrees with a wrong base string. Regenerate with (no double
   * quotes in the shell string, so it survives a copy-paste):
   *   node --input-type=module -e "import{createHmac}from'node:crypto';
   *     const s='whsec_dual_phase',b=JSON.stringify({workflow:'wf_1',run:7});
   *     const h=x=>createHmac('sha256',s).update(x).digest('hex');
   *     console.log(h('1760000000.POST./hooks/chatly?run=7.'+b),h('1760000000.'+b));"
   */
  private static final String DUAL_BODY = "{\"workflow\":\"wf_1\",\"run\":7}";

  private static final String DUAL_SECRET = "whsec_dual_phase";

  private static final String DUAL_METHOD = "POST";

  private static final String DUAL_PATH = "/hooks/chatly?run=7";

  private static final long DUAL_NOW_MS = 1_760_000_000_000L;

  private static final String DUAL_TARGET_DIGEST =
      "f1b09a141a4ac0a8b9e2902225312f679caa016cb26df48aa6a0e0cf1e4f4b36";

  private static final String DUAL_BODY_DIGEST =
      "8e5164b6f89caf31b699bc6e3d8b413d5f8edc870a21fc8788745bfaf565b874";

  /** Exactly what the worker sends: both digests, in that order, no `b=` field. */
  private static final String DUAL_VALUE =
      "t=1760000000,v1=" + DUAL_TARGET_DIGEST + ",v1=" + DUAL_BODY_DIGEST;

  private static ChatlySignatureInput.Builder dual() {
    return ChatlySignatureInput.builder()
        .surface(ChatlySignatureSurface.WORKFLOW_WEBHOOK)
        .body(DUAL_BODY)
        .header("x-chatly-signature-v2", DUAL_VALUE)
        .secret(DUAL_SECRET)
        .nowMs(DUAL_NOW_MS);
  }

  @Test
  @DisplayName("dual: method and path given — the TARGET digest verifies")
  void theDualValueVerifiesTheTargetBase() {
    ChatlySignatureResult r =
        Webhooks.verifyChatlySignature(dual().method(DUAL_METHOD).path(DUAL_PATH).build());
    assertTrue(r.ok(), r.message());
    assertEquals("workflow_webhook", r.surface());
    assertEquals(ChatlySignatureResult.Base.TARGET, r.base());
    assertEquals(0, r.secretIndex());
    assertEquals(DUAL_NOW_MS, r.timestampMs());
  }

  @Test
  @DisplayName("dual: no method or path — the BODY digest still verifies")
  void theDualValueVerifiesTheBodyBaseWithNoTarget() {
    ChatlySignatureResult r = Webhooks.verifyChatlySignature(dual().build());
    assertTrue(r.ok(), r.message());
    assertEquals(ChatlySignatureResult.Base.BODY, r.base());
  }

  @Test
  @DisplayName("dual: as unified_v2 it is the target base, and nothing else")
  void theDualValueVerifiesAsUnifiedV2() {
    ChatlySignatureResult r =
        Webhooks.verifyChatlySignature(
            dual().surface(ChatlySignatureSurface.UNIFIED_V2).method(DUAL_METHOD).path(DUAL_PATH).build());
    assertTrue(r.ok(), r.message());
    assertEquals("unified_v2", r.surface());
    assertEquals(ChatlySignatureResult.Base.TARGET, r.base());

    // unified_v2 accepts only the target base, so the body digest sitting beside it buys a
    // receiver with no method/path nothing: it is a named refusal, not a quiet downgrade.
    ChatlySignatureResult noTarget =
        Webhooks.verifyChatlySignature(dual().surface(ChatlySignatureSurface.UNIFIED_V2).build());
    assertEquals("missing_request_target", noTarget.reasonCode(), noTarget.message());
  }

  @Test
  @DisplayName("dual: the method is case-normalised, as the base string says")
  void theDualValueNormalisesTheMethodCase() {
    ChatlySignatureResult r =
        Webhooks.verifyChatlySignature(dual().method("post").path(DUAL_PATH).build());
    assertTrue(r.ok(), r.message());
    assertEquals(ChatlySignatureResult.Base.TARGET, r.base());
  }

  @Test
  @DisplayName("dual: a rewritten path falls to the body base under PREFER and is REFUSED under REQUIRE")
  void theDualValueUnderAWrongPath() {
    // The cost of the dual phase, stated: a proxy that rewrote the path does not break the
    // delivery, because the body digest is still there — but `base()` says BODY, so a receiver
    // can SEE that the path was not covered.
    ChatlySignatureResult prefer =
        Webhooks.verifyChatlySignature(dual().method(DUAL_METHOD).path("/elsewhere").build());
    assertTrue(prefer.ok(), prefer.message());
    assertEquals(ChatlySignatureResult.Base.BODY, prefer.base());

    // Which is why a receiver that wants the path bound in has to ask for it.
    ChatlySignatureResult require =
        Webhooks.verifyChatlySignature(
            dual()
                .method(DUAL_METHOD)
                .path("/elsewhere")
                .v2(ChatlySignatureInput.V2Policy.REQUIRE)
                .build());
    assertEquals("signature_mismatch", require.reasonCode(), require.message());

    ChatlySignatureResult requireRight =
        Webhooks.verifyChatlySignature(
            dual()
                .method(DUAL_METHOD)
                .path(DUAL_PATH)
                .v2(ChatlySignatureInput.V2Policy.REQUIRE)
                .build());
    assertTrue(requireRight.ok(), requireRight.message());
    assertEquals(ChatlySignatureResult.Base.TARGET, requireRight.base());
  }

  @Test
  @DisplayName("dual: one byte of body changed fails BOTH digests")
  void oneByteOfBodyBreaksBothDualDigests() {
    ChatlySignatureResult r =
        Webhooks.verifyChatlySignature(
            dual()
                .body(DUAL_BODY.replace("\"run\":7", "\"run\":8"))
                .method(DUAL_METHOD)
                .path(DUAL_PATH)
                .build());
    assertEquals("signature_mismatch", r.reasonCode(), r.message());
  }

  @Test
  @DisplayName("dual: 300s is inside the window, 301s is not")
  void theDualValueIsBoundedByTheTolerance() {
    assertTrue(
        Webhooks.verifyChatlySignature(
                dual().method(DUAL_METHOD).path(DUAL_PATH).nowMs(DUAL_NOW_MS + 300_000).build())
            .ok(),
        "exactly the tolerance is inside, in every SDK");
    assertEquals(
        "timestamp_outside_tolerance",
        Webhooks.verifyChatlySignature(
                dual().method(DUAL_METHOD).path(DUAL_PATH).nowMs(DUAL_NOW_MS + 301_000).build())
            .reasonCode());
  }

  @Test
  @DisplayName("dual: the value arriving twice is a duplicate, not two chances")
  void twoDualHeadersAreADuplicate() {
    ChatlySignatureResult r =
        Webhooks.verifyChatlySignature(
            dual()
                .header("x-chatly-signature-v2", DUAL_VALUE)
                .method(DUAL_METHOD)
                .path(DUAL_PATH)
                .build());
    assertEquals("duplicate_signature_header", r.reasonCode(), r.message());
  }

  @Test
  @DisplayName("dual: mid-rotation, the caller's own secret index is reported")
  void theDualValueReportsTheCallersSecretIndex() {
    ChatlySignatureResult r =
        Webhooks.verifyChatlySignature(
            dual()
                .secrets("", "whsec_wrong", DUAL_SECRET)
                .method(DUAL_METHOD)
                .path(DUAL_PATH)
                .build());
    assertTrue(r.ok(), r.message());
    assertEquals(2, r.secretIndex());
    assertEquals(ChatlySignatureResult.Base.TARGET, r.base());
  }

  // ---------------------------------------------------------------- exports

  @Test
  void theTableHasEveryReferenceSurfaceAndTheConstantsMatch() {
    Set<String> names = new HashSet<>();
    for (ChatlySignatureSurface s : ChatlySignatureSurface.values()) {
      names.add(s.wireName());
      assertEquals(s, ChatlySignatureSurface.fromWireName(s.wireName()));
    }
    assertEquals(
        Set.of(
            "unified_v2", "agent_delivery", "event_webhook", "ai_tool_call", "canvas_step",
            "customer_card", "workflow_webhook", "workflow_webhook_legacy"),
        names);
    assertEquals(300L, ChatlySignatureSurface.TOLERANCE_SECONDS);
    assertEquals("x-chatly-signature-v2", ChatlySignatureSurface.V2_SIGNATURE_HEADER);
    assertEquals(11, ChatlySignatureResult.Reason.values().length);
  }
}
