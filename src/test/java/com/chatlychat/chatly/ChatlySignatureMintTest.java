package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chatlychat.chatly.ChatlySignatureMintInput.Base;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Webhooks#signChatlyV2} against digests the REFERENCE produced, and then through this
 * SDK's own verifier.
 *
 * <p><strong>The pinned digests are the point.</strong> They come from
 * {@code packages/sdk-node/src/signature-mint.ts}, not from this code: a mint asserted against its
 * own base-string construction agrees with a wrong construction, and the pair of them would then
 * pass every test while production refused every delivery. Regenerate with (no double quotes, so it
 * survives a copy-paste):
 *
 * <pre>
 * node --input-type=module -e "import{createHmac}from'node:crypto';
 *   const s='whsec_dual_phase',b=JSON.stringify({workflow:'wf_1',run:7});
 *   const h=x=>createHmac('sha256',s).update(x).digest('hex');
 *   console.log(h('1760000000.POST./hooks/chatly?run=7.'+b),h('1760000000.'+b));"
 * </pre>
 *
 * <p>Round-tripping the minted value through {@link Webhooks#verifyChatlySignature} is the second
 * half and not the first: the two could agree on a wrong base string, which is precisely what the
 * pinned digests rule out.
 */
class ChatlySignatureMintTest {

  private static final String SECRET = "whsec_dual_phase";

  private static final String BODY = "{\"workflow\":\"wf_1\",\"run\":7}";

  private static final long T = 1_760_000_000L;

  private static final String METHOD = "POST";

  private static final String PATH = "/hooks/chatly?run=7";

  /** HMAC-SHA256(SECRET, "1760000000.POST./hooks/chatly?run=7." + BODY), from the reference. */
  private static final String TARGET_DIGEST =
      "f1b09a141a4ac0a8b9e2902225312f679caa016cb26df48aa6a0e0cf1e4f4b36";

  /** HMAC-SHA256(SECRET, "1760000000." + BODY), from the reference. */
  private static final String BODY_DIGEST =
      "8e5164b6f89caf31b699bc6e3d8b413d5f8edc870a21fc8788745bfaf565b874";

  private static ChatlySignatureMintInput.Builder mint(Base base) {
    ChatlySignatureMintInput.Builder b =
        ChatlySignatureMintInput.builder().base(base).secret(SECRET).timestampSeconds(T).body(BODY);
    return base == Base.TARGET ? b.method(METHOD).path(PATH) : b;
  }

  @Test
  @DisplayName("the target base mints the digest the reference minted, with b=t")
  void theTargetMintMatchesTheReference() {
    assertEquals("t=1760000000,v1=" + TARGET_DIGEST + ",b=t", Webhooks.signChatlyV2(mint(Base.TARGET).build()));
  }

  @Test
  @DisplayName("the body base mints the digest the reference minted, with b=b")
  void theBodyMintMatchesTheReference() {
    assertEquals("t=1760000000,v1=" + BODY_DIGEST + ",b=b", Webhooks.signChatlyV2(mint(Base.BODY).build()));
  }

  @Test
  @DisplayName("the base string is the construction the table documents")
  void theBaseStringIsTheDocumentedConstruction() {
    assertArrayEquals(
        ("1760000000.POST./hooks/chatly?run=7." + BODY).getBytes(StandardCharsets.UTF_8),
        Webhooks.chatlyV2BaseString(mint(Base.TARGET).build()));
    assertArrayEquals(
        ("1760000000." + BODY).getBytes(StandardCharsets.UTF_8),
        Webhooks.chatlyV2BaseString(mint(Base.BODY).build()));
  }

  @Test
  @DisplayName("the method is upper-cased, as the verifier upper-cases it")
  void theMethodIsUpperCased() {
    assertEquals(
        Webhooks.signChatlyV2(mint(Base.TARGET).build()),
        Webhooks.signChatlyV2(mint(Base.TARGET).method("post").build()));
  }

  @Test
  @DisplayName("a String body and its UTF-8 bytes mint the same value, non-ASCII included")
  void aStringBodyAndItsBytesAgree() {
    String unicode = "{\"name\":\"Ünïcödé — ✓\"}";
    assertEquals(
        Webhooks.signChatlyV2(mint(Base.TARGET).body(unicode).build()),
        Webhooks.signChatlyV2(
            mint(Base.TARGET).body(unicode.getBytes(StandardCharsets.UTF_8)).build()));
  }

  @Test
  @DisplayName("a minted value verifies, and as the base it says it is")
  void aMintedValueVerifies() {
    ChatlySignatureResult target =
        Webhooks.verifyChatlySignature(
            ChatlySignatureInput.builder()
                .surface(ChatlySignatureSurface.UNIFIED_V2)
                .body(BODY)
                .header("x-chatly-signature-v2", Webhooks.signChatlyV2(mint(Base.TARGET).build()))
                .secret(SECRET)
                .method(METHOD)
                .path(PATH)
                .nowMs(T * 1000)
                .build());
    assertTrue(target.ok(), target.message());
    assertEquals(ChatlySignatureResult.Base.TARGET, target.base());

    // b=b on unified_v2 is the refusal the marker exists to make legible.
    ChatlySignatureResult bodyOnUnified =
        Webhooks.verifyChatlySignature(
            ChatlySignatureInput.builder()
                .surface(ChatlySignatureSurface.UNIFIED_V2)
                .body(BODY)
                .header("x-chatly-signature-v2", Webhooks.signChatlyV2(mint(Base.BODY).build()))
                .secret(SECRET)
                .method(METHOD)
                .path(PATH)
                .nowMs(T * 1000)
                .build());
    assertEquals("signature_mismatch", bodyOnUnified.reasonCode(), bodyOnUnified.message());

    // …and the same value is what workflow_webhook, which accepts both, is for.
    ChatlySignatureResult bodyOnWorkflow =
        Webhooks.verifyChatlySignature(
            ChatlySignatureInput.builder()
                .surface(ChatlySignatureSurface.WORKFLOW_WEBHOOK)
                .body(BODY)
                .header("x-chatly-signature-v2", Webhooks.signChatlyV2(mint(Base.BODY).build()))
                .secret(SECRET)
                .method(METHOD)
                .path(PATH)
                .nowMs(T * 1000)
                .build());
    assertTrue(bodyOnWorkflow.ok(), bodyOnWorkflow.message());
    assertEquals(ChatlySignatureResult.Base.BODY, bodyOnWorkflow.base());
  }

  @Test
  @DisplayName("two mints compose the workflow action's live dual-digest value")
  void twoMintsComposeTheDualDigestValue() {
    // What `fire-webhook.ts` sends while WORKFLOW_WEBHOOK_V2_PHASE is 'dual': the v1 field out of
    // each mint, one `t`, and NO marker — because the value carries two constructions at once and
    // a marker can only name one of them.
    String dual =
        "t="
            + T
            + ",v1="
            + v1Of(Webhooks.signChatlyV2(mint(Base.TARGET).build()))
            + ",v1="
            + v1Of(Webhooks.signChatlyV2(mint(Base.BODY).build()));
    assertEquals("t=1760000000,v1=" + TARGET_DIGEST + ",v1=" + BODY_DIGEST, dual);

    ChatlySignatureResult withTarget =
        Webhooks.verifyChatlySignature(
            ChatlySignatureInput.builder()
                .surface(ChatlySignatureSurface.WORKFLOW_WEBHOOK)
                .body(BODY)
                .header("x-chatly-signature-v2", dual)
                .secret(SECRET)
                .method(METHOD)
                .path(PATH)
                .nowMs(T * 1000)
                .build());
    assertTrue(withTarget.ok(), withTarget.message());
    assertEquals(ChatlySignatureResult.Base.TARGET, withTarget.base());

    ChatlySignatureResult withoutTarget =
        Webhooks.verifyChatlySignature(
            ChatlySignatureInput.builder()
                .surface(ChatlySignatureSurface.WORKFLOW_WEBHOOK)
                .body(BODY)
                .header("x-chatly-signature-v2", dual)
                .secret(SECRET)
                .nowMs(T * 1000)
                .build());
    assertTrue(withoutTarget.ok(), withoutTarget.message());
    assertEquals(ChatlySignatureResult.Base.BODY, withoutTarget.base());
  }

  /** The `v1=` field of a minted value, as the producer's own `digestOf` does. */
  private static String v1Of(String value) {
    for (String part : value.split(",")) {
      if (part.startsWith("v1=")) {
        return part.substring("v1=".length());
      }
    }
    throw new AssertionError("no v1= in " + value);
  }

  @Test
  @DisplayName("an input that cannot be signed faithfully is refused, not signed")
  void anUnsignableInputIsRefused() {
    List<ChatlySignatureMintInput.Builder> bad =
        Arrays.asList(
            // no base: the two constructions are indistinguishable without the marker.
            ChatlySignatureMintInput.builder().secret(SECRET).timestampSeconds(T).body(BODY),
            // an empty secret is a valid HMAC key, which is why it has to be refused here.
            mint(Base.TARGET).secret(""),
            mint(Base.TARGET).secret(null),
            // milliseconds are the trap this field's name is fighting; a negative is the only
            // shape a long can be refused on.
            mint(Base.TARGET).timestampSeconds(-1L),
            // an empty body is `new byte[0]`; null is a caller who lost it.
            mint(Base.TARGET).body((byte[]) null),
            mint(Base.TARGET).body((String) null),
            // TARGET signs the method and the path, so neither can be guessed.
            mint(Base.TARGET).method(null),
            mint(Base.TARGET).method(""),
            mint(Base.TARGET).path(null),
            mint(Base.TARGET).path(""));
    for (int i = 0; i < bad.size(); i++) {
      final int index = i;
      assertThrows(
          IllegalArgumentException.class, () -> bad.get(index).build(), "bad[" + index + "]");
    }
    assertThrows(IllegalArgumentException.class, () -> Webhooks.signChatlyV2(null));
    assertThrows(IllegalArgumentException.class, () -> Webhooks.chatlyV2BaseString(null));
  }

  @Test
  @DisplayName("BODY ignores a method and a path rather than refusing them")
  void theBodyBaseIgnoresMethodAndPath() {
    // The reference documents them as "ignored for body", and a test harness that sets every
    // field once and switches only the base must keep minting the same value.
    assertEquals(
        Webhooks.signChatlyV2(mint(Base.BODY).build()),
        Webhooks.signChatlyV2(mint(Base.BODY).method("GET").path("/somewhere/else").build()));
  }

  @Test
  @DisplayName("the body is copied, so a caller mutating their buffer cannot change the digest")
  void theBodyIsCopiedIntoTheInput() {
    byte[] raw = BODY.getBytes(StandardCharsets.UTF_8);
    ChatlySignatureMintInput input = mint(Base.TARGET).body(raw).build();
    Arrays.fill(raw, (byte) 'x');
    assertEquals("t=1760000000,v1=" + TARGET_DIGEST + ",b=t", Webhooks.signChatlyV2(input));
  }
}
