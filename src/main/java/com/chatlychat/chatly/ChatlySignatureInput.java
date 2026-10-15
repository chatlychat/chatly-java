package com.chatlychat.chatly;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Everything {@link Webhooks#verifyChatlySignature} needs to decide one request.
 *
 * <pre>{@code
 * ChatlySignatureInput in = ChatlySignatureInput.builder()
 *     .surface(ChatlySignatureSurface.EVENT_WEBHOOK)
 *     .body(rawBody)                       // byte[] — the bytes as received
 *     .headers(request.headers().map())    // java.net.http / Spring HttpHeaders / any multimap
 *     .secret(System.getenv("CHATLY_WEBHOOK_SECRET"))
 *     .method(request.method())
 *     .path(pathAndQuery)
 *     .build();
 * }</pre>
 *
 * <p><strong>Why a builder and not a record.</strong> Ten fields, seven of them optional, and
 * three with two legitimate types each (body as bytes or text, secret as one or a rotation list,
 * surface as the enum or its wire string). A record would make every caller pass seven nulls in a
 * fixed order, which is how a method and a path end up swapped.
 *
 * <p><strong>Nothing here throws.</strong> Null is accepted for every field and becomes a refusal
 * with a reason when verified — the builder is on the hot path of a webhook handler, and an
 * exception thrown from it is exactly as much an accepted webhook in a handler that forgot a
 * {@code try/catch} as one thrown by the verifier.
 */
public final class ChatlySignatureInput {

  /** How hard to insist on the unified v2 construction. */
  public enum V2Policy {
    /**
     * The default. If {@code x-chatly-signature-v2} is present AND method and path were given,
     * verify THAT and nothing else; otherwise verify the surface's own legacy construction. A
     * receiver that passes method and path gets the stronger check the day the sender starts
     * emitting v2, with no code change, and is unaffected until then. Never cross-falls-back: once
     * v2 is chosen, a mismatched v2 digest is a refusal, not a reason to try the legacy header —
     * both come from the same secret, so a good legacy digest beside a bad v2 one is tampering or a
     * sender bug, and accepting it would hide both.
     */
    PREFER,
    /** Refuse unless the v2 header is there. For a receiver who knows the sender has moved. */
    REQUIRE,
    /** The surface's own construction only. */
    IGNORE
  }

  final String surface;
  final byte[] body;
  /** Every (name, value) pair, in the order given, duplicates and casings preserved. */
  final List<Map.Entry<String, String>> headers;
  /** The caller's list, empties included, so an index into it means what the caller thinks. */
  final List<String> secrets;
  final Long toleranceSeconds;
  final String method;
  final String path;
  final V2Policy v2;
  final boolean allowUnprotectedReplay;
  final Long nowMs;

  private ChatlySignatureInput(Builder b) {
    this.surface = b.surface;
    this.body = b.body;
    this.headers = Collections.unmodifiableList(new ArrayList<>(b.headers));
    this.secrets = b.secrets == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(b.secrets));
    this.toleranceSeconds = b.toleranceSeconds;
    this.method = b.method;
    this.path = b.path;
    this.v2 = b.v2 == null ? V2Policy.PREFER : b.v2;
    this.allowUnprotectedReplay = b.allowUnprotectedReplay;
    this.nowMs = b.nowMs;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable, single-use-or-not, never throws. */
  public static final class Builder {
    private String surface;
    private byte[] body;
    private final List<Map.Entry<String, String>> headers = new ArrayList<>();
    private List<String> secrets;
    private Long toleranceSeconds;
    private String method;
    private String path;
    private V2Policy v2;
    private boolean allowUnprotectedReplay;
    private Long nowMs;

    private Builder() {}

    /** Which surface this request came from. See {@link ChatlySignatureSurface}. */
    public Builder surface(ChatlySignatureSurface surface) {
      this.surface = surface == null ? null : surface.wireName();
      return this;
    }

    /**
     * The surface by its wire name, e.g. {@code "event_webhook"}. Anything that is not one of the
     * eight comes back as {@code unknown_surface} with the string echoed, not as an exception.
     */
    public Builder surface(String wireName) {
      this.surface = wireName;
      return this;
    }

    /**
     * The EXACT bytes received, before any JSON parsing. Re-serialising parsed JSON reorders keys
     * and changes whitespace, and the digest will not match.
     */
    public Builder body(byte[] rawBody) {
      this.body = rawBody;
      return this;
    }

    /** As {@link #body(byte[])}, for a body already in hand as text. Encoded as UTF-8. */
    public Builder body(String rawBody) {
      this.body = rawBody == null ? null : rawBody.getBytes(StandardCharsets.UTF_8);
      return this;
    }

    /**
     * Add every header from a multimap — {@code java.net.http.HttpHeaders.map()}, Spring's {@code
     * HttpHeaders}, a JAX-RS {@code MultivaluedMap}, or anything shaped like them.
     *
     * <p>Appends rather than replaces, and keeps every value under every key. Lookup is
     * case-insensitive and collects across keys, so {@code {"X-A": [v], "x-a": [w]}} is TWO values
     * of {@code x-a}: a map that kept two casings apart is a request that carried the header twice,
     * and for a signature header that is a refusal, not a choice.
     *
     * <p>Null keys and null values are skipped. A non-String smuggled in through a raw type is
     * skipped too, rather than throwing a {@code ClassCastException} out of a webhook handler.
     */
    public Builder headers(Map<String, ? extends Collection<String>> headers) {
      if (headers == null) {
        return this;
      }
      try {
        for (Map.Entry<String, ? extends Collection<String>> e : headers.entrySet()) {
          Object name = e.getKey();
          Object values = e.getValue();
          if (!(name instanceof String) || !(values instanceof Collection)) {
            continue;
          }
          for (Object value : (Collection<?>) values) {
            if (value instanceof String) {
              this.headers.add(new AbstractMap.SimpleImmutableEntry<>((String) name, (String) value));
            }
          }
        }
      } catch (RuntimeException hostileMap) {
        // A map whose iterator throws (concurrently modified, or a lazy view over a closed
        // request) is treated as however much of it was read. Whatever is missing makes the
        // verification refuse with a reason, which is the safe direction.
      }
      return this;
    }

    /** Add one header value. Call it twice with the same name and the header arrived twice. */
    public Builder header(String name, String value) {
      if (name != null && value != null) {
        this.headers.add(new AbstractMap.SimpleImmutableEntry<>(name, value));
      }
      return this;
    }

    /** The secret for THIS surface. No secret verifies across two surfaces. */
    public Builder secret(String secret) {
      this.secrets = secret == null ? null : Collections.singletonList(secret);
      return this;
    }

    /**
     * Every secret currently live on this surface, for a receiver mid-rotation. {@link
     * ChatlySignatureResult#secretIndex()} says which one verified, as an index into THIS list.
     */
    public Builder secrets(List<String> secrets) {
      // Copied with nulls kept (List.copyOf would throw on one): the index has to stay the
      // caller's index, and a null is skipped at verify time exactly like an empty string.
      this.secrets = secrets == null ? null : new ArrayList<>(secrets);
      return this;
    }

    /** As {@link #secrets(List)}. */
    public Builder secrets(String... secrets) {
      this.secrets = secrets == null ? null : new ArrayList<>(Arrays.asList(secrets));
      return this;
    }

    /**
     * Freshness window, always in SECONDS whatever unit the surface's timestamp is in. Default
     * {@link ChatlySignatureSurface#TOLERANCE_SECONDS}. Rejects stale AND far-future timestamps.
     * {@link Long#MAX_VALUE} disables the check — only for re-verifying something from an archive.
     */
    public Builder toleranceSeconds(long toleranceSeconds) {
      this.toleranceSeconds = toleranceSeconds;
      return this;
    }

    /**
     * The HTTP method, which is inside the signed string on {@code unified_v2} and {@code
     * ai_tool_call}. Case is normalised. Supplying it on any surface is what lets {@link
     * V2Policy#PREFER} pick up the v2 header when it appears.
     */
    public Builder method(String method) {
      this.method = method;
      return this;
    }

    /** {@code pathname + search} of the request as it arrived. Never the scheme or the host. */
    public Builder path(String path) {
      this.path = path;
      return this;
    }

    /** How hard to insist on the v2 header. Default {@link V2Policy#PREFER}. */
    public Builder v2(V2Policy policy) {
      this.v2 = policy;
      return this;
    }

    /**
     * {@code workflow_webhook_legacy} only, and required there. That surface signs the body with
     * NO timestamp, so a captured request verifies forever; verifying it has to be a deliberate
     * choice, and the default is a refusal that names {@code workflow_webhook} instead.
     */
    public Builder allowUnprotectedReplay(boolean allow) {
      this.allowUnprotectedReplay = allow;
      return this;
    }

    /** Escape hatch for tests: "now", as unix MILLISECONDS. Default the system clock. */
    public Builder nowMs(long nowMs) {
      this.nowMs = nowMs;
      return this;
    }

    public ChatlySignatureInput build() {
      return new ChatlySignatureInput(this);
    }
  }
}
