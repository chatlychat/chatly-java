package com.chatlychat.chatly;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The backend half of widget identity verification.
 *
 * <p>When a workspace turns identity verification on, the browser widget must prove that the
 * {@code externalId} it claims was issued by your backend rather than typed into a browser console.
 * Without it, anyone who reads your page source can open a chat as any of your users and read that
 * person's conversation history. This class computes the proof.
 *
 * <pre>{@code
 * // server-side, rendering the page
 * String userHash = Identity.hash(System.getenv("CHATLY_IDV_SECRET"), user.getId());
 * model.addAttribute("chatlyUserHash", userHash);
 * }</pre>
 *
 * <pre>{@code
 * // in the page
 * Chatly('identify', { externalId: "{{user.id}}", userHash: "{{chatlyUserHash}}",
 *                      email: "{{user.email}}" });
 * }</pre>
 *
 * <p><strong>The secret must never reach a browser.</strong> Only the digest does. Putting the
 * {@code sek_…} secret in client-side JavaScript, a mobile app bundle, or a public build-time
 * environment variable hands every visitor the ability to mint a hash for any user id, which
 * defeats the entire mechanism — and a secret in a bundle is not revocable without a redeploy. If
 * you need the hash in a single-page app, compute it on your server and send it down with the
 * session, as above.
 */
public final class Identity {

  private Identity() {}

  /**
   * Compute the {@code userHash} for one external id.
   *
   * <p>Lowercase hex HMAC-SHA256 of {@code externalId} under the workspace's identity-verification
   * secret. Known vector, cross-checked against the Node and Python SDKs:
   *
   * <pre>{@code
   * Identity.hash("sek_test_secret", "user_42")
   *   == "5e26772dce97d5ba995796e621f83f2e97e6f8c3f9386dbf153a98ab24c10271"
   * }</pre>
   *
   * @param secret the workspace's identity-verification secret, from Settings → Widget → Identity
   *     verification. Starts {@code sek_}.
   * @param externalId your own stable identifier for the user — the same value you pass as
   *     {@code externalId} to {@code identify}. Must be the identifier, not an email, unless your
   *     {@code externalId} <em>is</em> the email.
   * @throws IllegalArgumentException if either argument is null or empty. This throws rather than
   *     returning a hash of nothing: an empty secret produces a perfectly well-formed digest that
   *     anyone can reproduce, so silently accepting one would turn identity verification off while
   *     leaving every outward sign that it is on. An unset environment variable is exactly how that
   *     happens.
   */
  public static String hash(String secret, String externalId) {
    if (secret == null || secret.isEmpty()) {
      throw new IllegalArgumentException(
          "Identity.hash: secret is empty — check CHATLY_IDV_SECRET is actually set. Hashing under"
              + " an empty secret yields a digest any visitor could compute.");
    }
    if (externalId == null || externalId.isEmpty()) {
      throw new IllegalArgumentException("Identity.hash: externalId is empty");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return toHex(mac.doFinal(externalId.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException impossible) {
      // HmacSHA256 is mandated by the Java SE spec; a non-empty key is always
      // valid for it. Unlike the webhook verifier, this one rethrows: a hash
      // that cannot be computed must not be mistaken for a hash that failed to
      // match, and there is no "false" to return.
      throw new IllegalStateException("HmacSHA256 is unavailable on this JVM", impossible);
    }
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /** Lowercase hex. Shared with {@link Webhooks}. */
  static String toHex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2] = HEX[v >>> 4];
      out[i * 2 + 1] = HEX[v & 0x0F];
    }
    return new String(out);
  }
}
