package com.chatlychat.chatly;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verify the signature on a webhook delivery from Chatly.
 *
 * <p>Every delivery carries {@code x-livechat-signature: t=<unix seconds>,v1=<hex>}, where
 * {@code v1} is {@code HMAC-SHA256(secret, "<t>." + rawBody)}. The timestamp is bound
 * <strong>into</strong> the digest, which is what makes the replay window enforceable at all: if
 * {@code t} were merely a header beside a digest over the body, an attacker could replay a genuine
 * body with a fresh {@code t} and the digest would still check out.
 *
 * <p>Usage in a Spring controller — note {@code byte[]}, not a bound object:
 *
 * <pre>{@code
 * @PostMapping(path = "/webhooks/chatly", consumes = MediaType.ALL_VALUE)
 * public ResponseEntity<Void> onEvent(
 *     @RequestBody byte[] rawBody,
 *     @RequestHeader("x-livechat-signature") String signature) {
 *   if (!Webhooks.verifySignature(rawBody, signature, System.getenv("CHATLY_WEBHOOK_SECRET"))) {
 *     return ResponseEntity.status(401).build();
 *   }
 *   Map<String, Object> event = Json.parseObject(new String(rawBody, StandardCharsets.UTF_8));
 *   ...
 *   return ResponseEntity.ok().build();
 * }
 * }</pre>
 *
 * <p><strong>Verify the bytes you received.</strong> Taking a {@code @RequestBody Map} or a Jackson
 * POJO and re-serialising it reorders keys and changes whitespace, and the digest will not match —
 * so the handler rejects 100% of genuine deliveries and the cause is invisible, because the parsed
 * object looks perfectly correct in a debugger. That is why every method here takes bytes or a
 * string and none takes an object.
 */
public final class Webhooks {

  private Webhooks() {}

  /** The header Chatly signs with. */
  public static final String SIGNATURE_HEADER = "x-livechat-signature";

  /** Default freshness window for {@code t}: five minutes either side of now. */
  public static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(300);

  /**
   * Verify a delivery with the default five-minute tolerance.
   *
   * @param rawBody the exact bytes of the request body
   * @param signatureHeader the {@code x-livechat-signature} header value
   * @param secret the endpoint's {@code whsec_…} signing secret
   * @return true only if the signature is genuine and {@code t} is inside the tolerance
   */
  public static boolean verifySignature(byte[] rawBody, String signatureHeader, String secret) {
    return verifySignature(
        rawBody, signatureHeader, secret, DEFAULT_TOLERANCE, Instant.now().getEpochSecond());
  }

  /** As {@link #verifySignature(byte[], String, String)}, for a body you already have as a String. */
  public static boolean verifySignature(String rawBody, String signatureHeader, String secret) {
    return verifySignature(
        utf8(rawBody), signatureHeader, secret, DEFAULT_TOLERANCE, Instant.now().getEpochSecond());
  }

  /**
   * Verify with an explicit tolerance.
   *
   * @param tolerance how far {@code t} may be from now. Pass {@code null} to accept any timestamp —
   *     correct only when re-verifying an archived delivery, never in a live handler, because it
   *     disables replay protection entirely.
   */
  public static boolean verifySignature(
      byte[] rawBody, String signatureHeader, String secret, Duration tolerance) {
    return verifySignature(
        rawBody, signatureHeader, secret, tolerance, Instant.now().getEpochSecond());
  }

  /**
   * The full form. Every overload above funnels here.
   *
   * <p><strong>Returns false; never throws.</strong> Not for a malformed header, not for a missing
   * field, not for a non-numeric {@code t}, not for a stale timestamp, not for a null secret, not
   * for odd-length hex. A verifier that throws is an <em>accepted</em> webhook in a handler that
   * forgot a {@code try/catch} — the exception propagates, the framework turns it into a 500, our
   * delivery system retries it, and whoever wrote the handler never learns that unsigned traffic was
   * reaching it. Refusing by return value is the only shape that fails closed.
   *
   * @param nowEpochSeconds the current time, as unix seconds. Injectable so the pinned test vector
   *     from 2023 can be verified without freezing the system clock.
   */
  public static boolean verifySignature(
      byte[] rawBody,
      String signatureHeader,
      String secret,
      Duration tolerance,
      long nowEpochSeconds) {

    if (rawBody == null || signatureHeader == null || secret == null || secret.isEmpty()) {
      return false;
    }

    String timestamp = null;
    String providedHex = null;
    for (String part : signatureHeader.split(",")) {
      int eq = part.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String name = part.substring(0, eq).trim();
      String value = part.substring(eq + 1).trim();
      if ("t".equals(name) && timestamp == null) {
        timestamp = value;
      } else if ("v1".equals(name) && providedHex == null) {
        providedHex = value;
      }
    }
    if (timestamp == null || providedHex == null || timestamp.isEmpty() || providedHex.isEmpty()) {
      return false;
    }

    long issuedAt;
    try {
      issuedAt = Long.parseLong(timestamp);
    } catch (NumberFormatException notANumber) {
      return false;
    }

    if (tolerance != null) {
      long skew = Math.abs(nowEpochSeconds - issuedAt);
      if (skew > tolerance.getSeconds()) {
        return false;
      }
    }

    byte[] expected;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(utf8(secret), "HmacSHA256"));
      // The signed payload is the timestamp, a literal '.', then the body.
      // Signing the body ALONE is the bug that shipped in the Node SDK and
      // rejected every genuine delivery the platform sent. The timestamp here
      // is the string from the header, NOT a re-formatted parse of it: a
      // producer sending `t=01700000000` signed those eleven characters, and
      // round-tripping through a long would sign ten different ones.
      mac.update(utf8(timestamp));
      mac.update((byte) '.');
      mac.update(rawBody);
      expected = mac.doFinal();
    } catch (GeneralSecurityException impossible) {
      // HmacSHA256 is mandatory on every Java SE platform, and a SecretKeySpec
      // of non-zero length is always a valid key for it. Unreachable in
      // practice; returning false rather than rethrowing keeps the promise in
      // this method's contract that it never throws.
      return false;
    }

    byte[] provided = decodeHex(providedHex);
    if (provided == null) {
      return false;
    }
    // Length first. MessageDigest.isEqual is constant-time for equal-length
    // inputs and short-circuits on a length mismatch, which leaks only the
    // length -- but comparing explicitly makes the intent reviewable.
    return provided.length == expected.length && MessageDigest.isEqual(provided, expected);
  }

  /** As above, for a body already in hand as a String. */
  public static boolean verifySignature(
      String rawBody,
      String signatureHeader,
      String secret,
      Duration tolerance,
      long nowEpochSeconds) {
    return verifySignature(utf8(rawBody), signatureHeader, secret, tolerance, nowEpochSeconds);
  }

  /**
   * Build the header a delivery would carry. For testing your own handler, and for nothing else —
   * it needs the signing secret, so it cannot be used to forge anything you could not already
   * forge.
   */
  public static String signatureHeaderFor(byte[] rawBody, String secret, long epochSeconds) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(utf8(secret), "HmacSHA256"));
      mac.update(utf8(Long.toString(epochSeconds)));
      mac.update((byte) '.');
      mac.update(rawBody);
      return "t=" + epochSeconds + ",v1=" + Identity.toHex(mac.doFinal());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  /**
   * Strict hex decode.
   *
   * @return null for odd length or any non-hex character — both are a malformed header, which is a
   *     {@code false}, not an exception
   */
  private static byte[] decodeHex(String hex) {
    int len = hex.length();
    if (len == 0 || (len & 1) != 0) {
      return null;
    }
    byte[] out = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      int hi = Character.digit(hex.charAt(i), 16);
      int lo = Character.digit(hex.charAt(i + 1), 16);
      if (hi < 0 || lo < 0) {
        return null;
      }
      out[i / 2] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  private static byte[] utf8(String s) {
    return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
  }
}
