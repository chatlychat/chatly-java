package com.chatlychat.chatly;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What to mint an {@code x-chatly-signature-v2} value over — the other direction from {@link
 * ChatlySignatureInput}.
 *
 * <p>Verifying is what your handler does in production; minting is what your TEST does, standing up
 * a fake Chatly and firing a signed request at your own endpoint. The entry points are {@link
 * Webhooks#signChatlyV2} and {@link Webhooks#chatlyV2BaseString}, so there is one class to find for
 * both directions.
 *
 * <p>A port of {@code signChatlyV2} / {@code chatlyV2BaseString} in {@code
 * packages/sdk-node/src/signature-mint.ts}, which is the reference: where this and that disagree
 * about the bytes, that one is right and this one has a bug. {@code ChatlySignatureMintTest} pins
 * both digests against values produced by the reference rather than by this code, because a mint
 * tested against its own base-string construction agrees with a wrong base string — and that is not
 * hypothetical, {@code packages/shared} once emitted a header in milliseconds over a seconds value
 * and a verifier following our own published docs refused 100% of real traffic.
 *
 * <p><strong>Why a builder and not six arguments.</strong> The construction is
 * {@code <t>.<METHOD>.<path>.<rawBody>} — three strings in a row. A positional
 * {@code signChatlyV2(secret, t, method, path, body)} compiles with {@code path} and {@code body}
 * swapped and mints a digest nobody can reproduce, which arrives at the receiver as "signature
 * mismatch" and sends whoever wrote it hunting their own secret. Named setters make that
 * unwriteable, which is the same reason {@link ChatlySignatureInput} has a builder.
 *
 * <p><strong>Not for signing real outbound traffic.</strong> Nothing in this package sends a signed
 * request to anyone; Chatly's own producers are {@code packages/shared/src/webhook-signature/v2.ts}.
 * This is here so a test can mint what Chatly would send.
 */
public final class ChatlySignatureMintInput {

  /**
   * Which string the MAC is taken over.
   *
   * <p>There is deliberately no default. The two constructions are indistinguishable from the value
   * alone — which is exactly why the {@code b=} marker exists — so a caller has to say which one
   * they mean. An ABSENT marker means {@link #BODY} on the wire, because every v2 signature minted
   * before the marker existed was that construction; this never emits an absent one.
   */
  public enum Base {
    /** {@code <t>.<METHOD>.<path>.<rawBody>}, the unified base. Emitted as {@code b=t}. */
    TARGET("t"),
    /** {@code <t>.<rawBody>}, the workflow action's pre-unified base. Emitted as {@code b=b}. */
    BODY("b");

    private final String marker;

    Base(String marker) {
      this.marker = marker;
    }

    /** The on-the-wire letter of the {@code b=} field. */
    public String marker() {
      return marker;
    }
  }

  final Base base;
  final String secret;
  final long timestampSeconds;
  final byte[] body;
  final String method;
  final String path;

  private ChatlySignatureMintInput(Builder b) {
    this.base = b.base;
    this.secret = b.secret;
    this.timestampSeconds = b.timestampSeconds;
    this.body = b.body;
    this.method = b.method;
    this.path = b.path;
  }

  /** A new builder. Every field is named; nothing is positional. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds a {@link ChatlySignatureMintInput}. */
  public static final class Builder {
    private Base base;
    private String secret;
    private long timestampSeconds = -1L;
    private byte[] body;
    private String method;
    private String path;

    private Builder() {}

    /** Which string to sign. Required — see {@link Base}. */
    public Builder base(Base base) {
      this.base = base;
      return this;
    }

    /** The surface's signing secret. Required, and an empty one is refused. */
    public Builder secret(String secret) {
      this.secret = secret;
      return this;
    }

    /**
     * Unix <strong>seconds</strong>, never milliseconds.
     *
     * <p>Two surfaces send millisecond timestamps in their LEGACY headers; the v2 header is seconds
     * on every one of them. A millisecond value here signs a {@code t} the receiver reads as the
     * year 55,000 and refuses as {@code timestamp_outside_tolerance}.
     */
    public Builder timestampSeconds(long timestampSeconds) {
      this.timestampSeconds = timestampSeconds;
      return this;
    }

    /** The exact bytes that will be sent as the body. Required; empty is fine, null is not. */
    public Builder body(byte[] rawBody) {
      this.body = rawBody == null ? null : rawBody.clone();
      return this;
    }

    /** As {@link #body(byte[])}, encoded UTF-8 — the MAC is always over bytes. */
    public Builder body(String rawBody) {
      this.body = rawBody == null ? null : rawBody.getBytes(StandardCharsets.UTF_8);
      return this;
    }

    /** The HTTP method. Required for {@link Base#TARGET}, ignored for {@link Base#BODY}. */
    public Builder method(String method) {
      this.method = method;
      return this;
    }

    /**
     * {@code pathname + query} of the request line — never the scheme, never the host. Required for
     * {@link Base#TARGET}, ignored for {@link Base#BODY}.
     */
    public Builder path(String path) {
      this.path = path;
      return this;
    }

    /**
     * @throws IllegalArgumentException on a missing base, an empty or missing secret, a negative
     *     timestamp, a null body, or a {@link Base#TARGET} with no method or path. Every one of
     *     them would otherwise mint a digest the receiver cannot reproduce, and "signature
     *     mismatch" is the least useful place to discover it. The reference is TypeScript and its
     *     types make most of these unreachable rather than checked, so this refuses where it merely
     *     never gets the chance to.
     */
    public ChatlySignatureMintInput build() {
      if (base == null) {
        throw new IllegalArgumentException(
            "signChatlyV2: base is required — Base.TARGET for <t>.<METHOD>.<path>.<body>, "
                + "Base.BODY for <t>.<body>. There is no default: the two are indistinguishable "
                + "without the b= marker this emits.");
      }
      if (secret == null || secret.isEmpty()) {
        throw new IllegalArgumentException(
            "signChatlyV2: secret is required and must not be empty. An empty string is a "
                + "perfectly valid HMAC key, which is the problem — the likeliest cause is an "
                + "environment variable that never got set.");
      }
      if (timestampSeconds < 0) {
        throw new IllegalArgumentException(
            "signChatlyV2: timestampSeconds is required and must not be negative — unix SECONDS, "
                + "not milliseconds.");
      }
      if (body == null) {
        throw new IllegalArgumentException(
            "signChatlyV2: body is required. An empty body is an empty array, not null.");
      }
      if (base == Base.TARGET && (method == null || method.isEmpty())) {
        throw new IllegalArgumentException(
            "signChatlyV2: Base.TARGET signs the METHOD, so method is required.");
      }
      if (base == Base.TARGET && (path == null || path.isEmpty())) {
        throw new IllegalArgumentException(
            "signChatlyV2: Base.TARGET signs the path, so path is required — pathname + query of "
                + "the request line, not the scheme or host.");
      }
      return new ChatlySignatureMintInput(this);
    }
  }

  /**
   * The exact bytes the MAC is taken over.
   *
   * <p>Bytes, where the Node reference returns a string, because the MAC is over bytes and a body is
   * not always valid UTF-8 text. For a {@code String} body the two are the same thing.
   */
  byte[] baseString() {
    String t = Long.toString(timestampSeconds);
    // The method is upper-cased here because the VERIFIER upper-cases it
    // (`baseStringPrefix` in ChatlySignatureVerifier); a lower-case method in
    // a mint would otherwise produce a value the verifier refuses.
    String prefix =
        base == Base.BODY
            ? t + "."
            : t + "." + method.toUpperCase(Locale.ROOT) + "." + path + ".";
    byte[] head = prefix.getBytes(StandardCharsets.UTF_8);
    byte[] out = Arrays.copyOf(head, head.length + body.length);
    System.arraycopy(body, 0, out, head.length, body.length);
    return out;
  }

  /** {@code t=…,v1=…,b=…}. See {@link Webhooks#signChatlyV2}. */
  String sign() {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "t="
          + timestampSeconds
          + ",v1="
          + Identity.toHex(mac.doFinal(baseString()))
          + ",b="
          + base.marker();
    } catch (GeneralSecurityException impossible) {
      // HmacSHA256 is mandatory on every Java SE platform and a non-empty key is always valid for
      // it. Unlike the verifier, this one THROWS: a mint that returned something unusable would be
      // signed garbage on the wire, and the verifier's reason for never throwing (a refusal that
      // reads as an acceptance) does not apply in this direction.
      throw new IllegalStateException("HmacSHA256 unavailable", impossible);
    }
  }
}
