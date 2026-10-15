package com.chatlychat.chatly;

import com.chatlychat.chatly.ChatlySignatureInput.V2Policy;
import com.chatlychat.chatly.ChatlySignatureResult.Base;
import com.chatlychat.chatly.ChatlySignatureResult.Reason;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The algorithm behind {@link Webhooks#verifyChatlySignature}. Package-private: the entry point is
 * {@code Webhooks}, so there is one name to find in an IDE.
 *
 * <p>A line-for-line port of {@code packages/sdk-node/src/signatures.ts}, which is the reference;
 * the eight backend SDKs share {@code src/test/resources/signature_vectors.json} and must agree on
 * every vector in it. Where Java forced a choice the TypeScript did not have to make, the comment at
 * that line says what was chosen and why.
 *
 * <p><strong>Never throws.</strong> CONTRACT.md §6: a verifier that throws is an accepted webhook in
 * a handler that forgot to catch. Every input comes back as a result; {@link #verify} additionally
 * wraps the whole algorithm in a catch-all mapped to {@code signature_mismatch}, so a bug here
 * fails CLOSED rather than out of somebody's request handler.
 */
final class ChatlySignatureVerifier {

  private ChatlySignatureVerifier() {}

  /**
   * 64 hex characters and nothing else. Checked BEFORE decoding: a lenient decoder handed {@code
   * "zz…"} produces a short or empty array, which would reach the compare as a length mismatch and
   * be reported as a wrong secret instead of a malformed header.
   */
  private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

  /**
   * ASCII digits only. Java's {@code \d} is ASCII unless UNICODE_CHARACTER_CLASS is set, which is
   * the same as JavaScript's — so {@code "١٧٦"} (Arabic-Indic digits) is malformed in both.
   */
  private static final Pattern DIGITS = Pattern.compile("\\d+");

  private static final String V2 = ChatlySignatureSurface.V2_SIGNATURE_HEADER;

  static ChatlySignatureResult verify(ChatlySignatureInput in) {
    String surface = in == null ? null : in.surface;
    try {
      if (in == null) {
        return ChatlySignatureResult.refused(
            null, Reason.UNKNOWN_SURFACE, "No input given: pass a ChatlySignatureInput.");
      }
      return verify(in, in.surface, in.v2);
    } catch (RuntimeException | StackOverflowError bug) {
      // Unreachable by design. If it is ever reached, refusing is the only safe answer. A
      // StackOverflowError (a regex on a hostile header) is caught for the same reason; other
      // Errors, like OutOfMemoryError, are the JVM's to handle and are not swallowed.
      return ChatlySignatureResult.refused(
          surface,
          Reason.SIGNATURE_MISMATCH,
          "The signature could not be verified (" + bug.getClass().getSimpleName() + ").");
    }
  }

  /**
   * The algorithm, with the surface and the v2 policy as parameters rather than read off the input:
   * the v2 step re-enters this for {@code unified_v2} with policy {@code IGNORE}, which in the
   * TypeScript is a spread of the input object with two fields replaced.
   */
  private static ChatlySignatureResult verify(
      ChatlySignatureInput in, String surface, V2Policy policy) {
    ChatlySignatureSurface spec = ChatlySignatureSurface.fromWireName(surface);
    if (spec == null) {
      return fail(
          surface,
          Reason.UNKNOWN_SURFACE,
          "\"" + surface + "\" is not a Chatly signature surface. One of: "
              + ChatlySignatureSurface.allWireNames() + ".");
    }

    // The CALLER's index survives the filter: `["", s]` must report 1 for `s`, because the index
    // is how a receiver mid-rotation learns which secret is still in use.
    List<int[]> secretIndexes = new ArrayList<>();
    for (int i = 0; i < in.secrets.size(); i++) {
      String s = in.secrets.get(i);
      if (s != null && !s.isEmpty()) {
        secretIndexes.add(new int[] {i});
      }
    }
    if (secretIndexes.isEmpty()) {
      return fail(
          surface,
          Reason.MISSING_SECRET,
          "No secret given for " + surface + ". Each surface has its own secret — "
              + spec.describe() + ". A secret from another surface will never verify.");
    }

    if (spec == ChatlySignatureSurface.WORKFLOW_WEBHOOK_LEGACY && !in.allowUnprotectedReplay) {
      return fail(
          surface,
          Reason.REPLAY_UNPROTECTED,
          "The legacy workflow header signs the body with no timestamp, so a captured request "
              + "verifies forever and nothing here can bound a replay. The same call also sends "
              + "x-chatly-signature-v2 — verify that instead, with surface \"workflow_webhook\". "
              + "If you must accept the legacy header, call allowUnprotectedReplay(true) and make "
              + "the endpoint idempotent.");
    }

    ChatlySignatureResult viaV2 = applyV2Policy(in, surface, spec, policy);
    if (viaV2 != null) {
      return viaV2;
    }

    // ── The digest header ─────────────────────────────────────────────────
    List<String> sigHeaders = headerValues(in, spec.signatureHeader());
    if (sigHeaders.isEmpty()) {
      return fail(
          surface,
          Reason.MISSING_SIGNATURE_HEADER,
          "No " + spec.signatureHeader() + " header. " + capitalise(spec.describe())
              + " always sends one; if the request carries a differently named signature header, "
              + "you have the wrong surface.");
    }
    if (sigHeaders.size() > 1) {
      // Two values means something between Chatly and you merged or injected one, and picking
      // either is a guess. Refuse rather than trial-verify both: trial-verifying lets an attacker
      // who can ADD a header ride along beside a genuine one.
      return fail(
          surface,
          Reason.DUPLICATE_SIGNATURE_HEADER,
          sigHeaders.size() + " " + spec.signatureHeader() + " headers arrived. Chatly sends "
              + "exactly one; a proxy merged or added one, and choosing between them would be a "
              + "guess.");
    }
    String rawSignature = jsTrim(sigHeaders.get(0));

    List<String> digestsHex = new ArrayList<>();
    String rawTimestamp = null;
    String marker = null;
    if (spec.format() == ChatlySignatureSurface.Format.TV1) {
      // Parse `t=…,v1=…[,b=…]`, collecting EVERY v1 (a sender mid-rotation signs with both
      // secrets). Spaces and unknown fields are tolerated — a future `v3` field must not break a
      // `v1` verifier, which is the whole point of the prefix — and the FIRST `t` and FIRST `b`
      // win, so a crafted second one appended on the wire cannot move the window or the base.
      for (String part : rawSignature.split(",", -1)) {
        int eq = part.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String key = jsTrim(part.substring(0, eq));
        String value = jsTrim(part.substring(eq + 1));
        if ("t".equals(key)) {
          if (rawTimestamp == null) {
            rawTimestamp = value;
          }
        } else if ("b".equals(key)) {
          if (marker == null) {
            marker = value;
          }
        } else if ("v1".equals(key)) {
          digestsHex.add(value);
        }
      }
      if (rawTimestamp == null || digestsHex.isEmpty()) {
        return fail(
            surface,
            Reason.MALFORMED_SIGNATURE_HEADER,
            spec.signatureHeader() + " should be \"t=<unix seconds>,v1=<hex>\" on " + surface
                + "; got \"" + preview(rawSignature) + "\".");
      }
    } else {
      if (!rawSignature.startsWith("sha256=")) {
        return fail(
            surface,
            Reason.MALFORMED_SIGNATURE_HEADER,
            spec.signatureHeader() + " should be \"sha256=<hex>\" on " + surface + "; got \""
                + preview(rawSignature) + "\". A \"t=…,v1=…\" value here means you have the "
                + "wrong surface — that is the other signature family.");
      }
      digestsHex.add(jsTrim(rawSignature.substring("sha256=".length())));
    }

    for (String digestHex : digestsHex) {
      if (!HEX_SHA256.matcher(digestHex).matches()) {
        return fail(
            surface,
            Reason.MALFORMED_SIGNATURE_HEADER,
            "A digest in " + spec.signatureHeader() + " is not 64 hex characters (got "
                + digestHex.length() + ")."
                + (digestsHex.size() > 1
                    ? " " + digestsHex.size()
                        + " v1 values arrived; every one has to be well formed."
                    : ""));
      }
    }

    // ── The timestamp, and its unit ───────────────────────────────────────
    Long timestampMs = null;
    if (spec.unit() != null) {
      String tsHeader = spec.timestampHeader();
      if (tsHeader != null) {
        List<String> tsHeaders = headerValues(in, tsHeader);
        if (tsHeaders.isEmpty()) {
          return fail(
              surface,
              Reason.MISSING_TIMESTAMP,
              "No " + tsHeader + " header. On " + surface + " the timestamp travels in its own "
                  + "header and is part of the signed string, so it cannot be reconstructed.");
        }
        rawTimestamp = jsTrim(tsHeaders.get(0));
      }
      if (rawTimestamp == null || rawTimestamp.isEmpty()) {
        return fail(surface, Reason.MISSING_TIMESTAMP, "No timestamp found for " + surface + ".");
      }
      // `Long.parseLong` alone would accept "+17" and "-17"; a timestamp is digits.
      if (!DIGITS.matcher(rawTimestamp).matches()) {
        return fail(
            surface,
            Reason.MALFORMED_TIMESTAMP,
            "The timestamp on " + surface + " is not a run of digits: \""
                + preview(rawTimestamp) + "\".");
      }
      // Overflow is MALFORMED, not "far future". JavaScript's Number reads twenty digits as a
      // finite float and would call it out of tolerance; a long cannot hold it at all, and
      // nothing Chatly sends is within nine orders of magnitude of 2^63 ms.
      long signedMs;
      try {
        long n = Long.parseLong(rawTimestamp);
        signedMs = spec.unit() == ChatlySignatureSurface.Unit.SECONDS ? Math.multiplyExact(n, 1000L) : n;
      } catch (NumberFormatException | ArithmeticException tooBig) {
        return fail(
            surface,
            Reason.MALFORMED_TIMESTAMP,
            "The timestamp on " + surface + " does not fit in 64-bit milliseconds: \""
                + preview(rawTimestamp) + "\".");
      }
      timestampMs = signedMs;

      long toleranceSeconds =
          in.toleranceSeconds == null ? ChatlySignatureSurface.TOLERANCE_SECONDS : in.toleranceSeconds;
      long nowMs = in.nowMs == null ? System.currentTimeMillis() : in.nowMs;
      long driftMs = absDifference(nowMs, signedMs);
      if (driftMs > toleranceMs(toleranceSeconds)) {
        // Strictly greater: exactly the tolerance is inside, in every SDK. Both directions are
        // refused and the message says which. A stale timestamp is a replay or a slow queue; a
        // FAR-FUTURE one is a sender clock or — far more often — the wrong unit, which lands
        // roughly 54,000 years out one way or 54 years the other and reads as "expired" unless
        // something says so.
        double driftYears = driftMs / (1000.0 * 60 * 60 * 24 * 365);
        String unitHint =
            driftYears > 1
                ? String.format(Locale.ROOT, " That is %.0f year(s) out, which is what a wrong UNIT"
                    + " looks like: %s sends %s.", driftYears, surface,
                    spec.unit() == ChatlySignatureSurface.Unit.SECONDS ? "seconds" : "milliseconds")
                : "";
        return fail(
            surface,
            Reason.TIMESTAMP_OUTSIDE_TOLERANCE,
            "The timestamp on " + surface + " is " + Math.round(driftMs / 1000.0) + "s "
                + (signedMs > nowMs ? "in the FUTURE" : "in the past") + ", outside the "
                + toleranceSeconds + "s tolerance." + unitHint);
      }
    }

    // ── The base string, and the compare ──────────────────────────────────
    Object bases = candidateBases(in, surface, spec, policy, marker);
    if (bases instanceof ChatlySignatureResult) {
      return (ChatlySignatureResult) bases;
    }
    @SuppressWarnings("unchecked")
    List<Base> candidates = (List<Base>) bases;
    if (candidates.contains(Base.TARGET) && !hasTarget(in)) {
      return fail(
          surface,
          Reason.MISSING_REQUEST_TARGET,
          surface + " signs <t>.<METHOD>.<path>.<rawBody>, so both method and path are required. "
              + "path is pathname + search of the request as Chatly rendered it — not the host, "
              + "not the scheme.");
    }
    if (in.body == null) {
      // The TypeScript cannot be handed a missing body without its own Buffer.from throwing into
      // its catch; here it is a named refusal, and still the same reason code.
      return fail(
          surface,
          Reason.SIGNATURE_MISMATCH,
          "No body given for " + surface + ". Pass the raw request bytes — an empty body is an "
              + "empty array, not null.");
    }

    List<byte[]> given = new ArrayList<>();
    for (String hex : digestsHex) {
      given.add(decodeHex(hex));
    }
    for (Base base : candidates) {
      byte[] prefix = baseStringPrefix(base, rawTimestamp, in).getBytes(StandardCharsets.UTF_8);
      for (int[] slot : secretIndexes) {
        int index = slot[0];
        byte[] expected = hmac(in.secrets.get(index), prefix, in.body);
        for (byte[] candidate : given) {
          // Length first. MessageDigest.isEqual is constant-time for equal lengths; comparing the
          // length explicitly keeps that property reviewable rather than a library detail.
          if (candidate.length == expected.length && MessageDigest.isEqual(candidate, expected)) {
            return ChatlySignatureResult.accepted(surface, timestampMs, base, index);
          }
        }
      }
    }

    return fail(
        surface,
        Reason.SIGNATURE_MISMATCH,
        "The digest does not match for " + surface + ". The three usual causes, in order: a "
            + "re-serialised body (pass the RAW bytes, not a re-encoding of the parsed object), the "
            + "secret from a different surface, or the wrong surface — " + spec.describe() + "."
            + (secretIndexes.size() > 1
                ? " None of the " + secretIndexes.size() + " secrets matched."
                : ""));
  }

  /**
   * The v2 decision, taken before anything is parsed. Returns a result when it has answered the
   * whole question — by delegating to {@code unified_v2} or by refusing — and null when
   * verification should continue on the surface asked for.
   */
  private static ChatlySignatureResult applyV2Policy(
      ChatlySignatureInput in, String surface, ChatlySignatureSurface spec, V2Policy policy) {
    if (policy == V2Policy.IGNORE || spec == ChatlySignatureSurface.UNIFIED_V2) {
      return null;
    }
    // A surface whose OWN header is the v2 header handles the two bases in its candidate list
    // instead; there is nothing to delegate to.
    if (V2.equals(spec.signatureHeader())) {
      return null;
    }
    boolean present = !headerValues(in, V2).isEmpty();
    if (policy == V2Policy.REQUIRE && !present) {
      return fail(
          surface,
          Reason.MISSING_SIGNATURE_HEADER,
          "v2 REQUIRE was asked for and this request carries no " + V2 + " header. "
              + spec.signatureHeader() + " alone means the sender has not moved to the unified "
              + "construction yet — use the default V2Policy.PREFER until it has.");
    }
    if (!present) {
      return null;
    }
    if (!hasTarget(in)) {
      if (policy == V2Policy.REQUIRE) {
        return fail(
            surface,
            Reason.MISSING_REQUEST_TARGET,
            "The unified " + V2 + " signs <t>.<METHOD>.<path>.<rawBody>, so both method and path "
                + "are required to verify it. path is pathname + search of the request as it "
                + "arrived — not the host, not the scheme.");
      }
      // PREFER with no target to verify against: the legacy header is still sent beside it, so
      // fall through rather than refuse a request the caller can verify.
      return null;
    }

    String unified = ChatlySignatureSurface.UNIFIED_V2.wireName();
    ChatlySignatureResult result = verify(in, unified, V2Policy.IGNORE);
    if (result.ok()) {
      return result;
    }
    // Never cross-fall-back to the legacy header once v2 was chosen. See V2Policy.PREFER.
    return fail(
        unified,
        result.reason(),
        result.message() + " (This request carried " + V2 + ", so it was verified as the unified "
            + "v2 construction rather than as " + surface + ". Use V2Policy.IGNORE to verify "
            + spec.signatureHeader() + " instead.)");
  }

  /**
   * Which strings the MAC may have been taken over, most specific first — or a refusal when the
   * value's {@code b=} marker rules every candidate out. Returns {@code List<Base>} or a result;
   * Java has no union type, and a two-field holder would be one more class for one call site.
   *
   * <p>The marker is read ONLY off the v2 header ({@code fromV2}) — no legacy header carries one —
   * and it NARROWS the candidates, never widens them past what the surface and policy allow,
   * because anyone on the wire can write a marker. An absent or unrecognised marker ({@code b=z})
   * changes nothing: every v2 value minted before the marker existed is absent.
   */
  private static Object candidateBases(
      ChatlySignatureInput in,
      String surface,
      ChatlySignatureSurface spec,
      V2Policy policy,
      String marker) {
    boolean fromV2 = V2.equals(spec.signatureHeader());
    if (fromV2 && "b".equals(marker) && (spec.base() == Base.TARGET || policy == V2Policy.REQUIRE)) {
      return fail(
          surface,
          Reason.SIGNATURE_MISMATCH,
          "This " + V2 + " is marked b=b: it was signed over <t>.<rawBody>, the workflow action's "
              + "pre-unified base. "
              + (spec == ChatlySignatureSurface.UNIFIED_V2
                  ? "unified_v2 accepts only b=t. Verify it with surface \"workflow_webhook\", "
                      + "which accepts both."
                  : "V2Policy.REQUIRE accepts only the unified base (b=t)."));
    }
    if (!spec.alsoAcceptsTargetBase() || policy == V2Policy.IGNORE) {
      return List.of(spec.base());
    }
    if ("t".equals(marker)) {
      return List.of(Base.TARGET);
    }
    if ("b".equals(marker) || !hasTarget(in)) {
      return List.of(spec.base());
    }
    // REQUIRE on this surface means the unified base and nothing else.
    return policy == V2Policy.REQUIRE ? List.of(Base.TARGET) : List.of(Base.TARGET, spec.base());
  }

  private static String baseStringPrefix(Base base, String rawTimestamp, ChatlySignatureInput in) {
    if (base == Base.BODY_ONLY) {
      return "";
    }
    // The RAW timestamp text, never a re-formatted parse: a sender writing `t=01760000000` signed
    // those eleven characters, and a round trip through a long would sign ten different ones.
    String t = rawTimestamp == null ? "" : rawTimestamp;
    if (base == Base.BODY) {
      return t + ".";
    }
    String method = in.method == null ? "" : in.method.toUpperCase(Locale.ROOT);
    return t + "." + method + "." + (in.path == null ? "" : in.path) + ".";
  }

  private static byte[] hmac(String secret, byte[] prefix, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(prefix);
      mac.update(body);
      return mac.doFinal();
    } catch (GeneralSecurityException impossible) {
      // HmacSHA256 is mandatory on every Java SE platform and a non-empty key is always valid.
      // An empty array can never equal a 32-byte digest, so this refuses rather than throws.
      return new byte[0];
    }
  }

  /**
   * Every value of a header, across every key that matches case-insensitively, in order. Locale.ROOT
   * because the default locale would fold {@code "X-CHATLY-SIGNATURE"} through Turkish dotless-i
   * rules on a server configured for Turkey and never match.
   */
  private static List<String> headerValues(ChatlySignatureInput in, String name) {
    String wanted = name.toLowerCase(Locale.ROOT);
    List<String> found = new ArrayList<>();
    for (Map.Entry<String, String> e : in.headers) {
      if (e.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
        found.add(e.getValue());
      }
    }
    return found;
  }

  private static boolean hasTarget(ChatlySignatureInput in) {
    return in.method != null && !in.method.isEmpty() && in.path != null && !in.path.isEmpty();
  }

  /** |a − b| without overflow; saturates at Long.MAX_VALUE, which is outside any tolerance. */
  private static long absDifference(long a, long b) {
    try {
      long d = Math.subtractExact(a, b);
      return d == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(d);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  /** Seconds to ms, saturating, so {@code Long.MAX_VALUE} means "no limit" instead of wrapping. */
  private static long toleranceMs(long toleranceSeconds) {
    try {
      return Math.multiplyExact(toleranceSeconds, 1000L);
    } catch (ArithmeticException overflow) {
      return toleranceSeconds > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }

  /**
   * JavaScript's {@code String.prototype.trim}, because the reference uses it and the corpus is
   * shared. Java's {@code trim()} strips every char ≤ U+0020 (including C0 controls JS keeps) and
   * none of U+00A0, U+FEFF or the U+2000 block (which JS strips); {@code strip()} differs again.
   * Either would let two SDKs disagree about the same header.
   */
  static String jsTrim(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && isJsWhitespace(s.charAt(start))) {
      start++;
    }
    while (end > start && isJsWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(start, end);
  }

  private static boolean isJsWhitespace(char c) {
    switch (c) {
      case '\t': case '\n': case 0x0B: case '\f': case '\r': case ' ':
      case 0x00A0: case 0x1680: case 0x2028: case 0x2029: case 0x202F: case 0x205F:
      case 0x3000: case 0xFEFF:
        return true;
      default:
        return c >= 0x2000 && c <= 0x200A;
    }
  }

  /** Only ever called on a string that has matched {@link #HEX_SHA256}. */
  private static byte[] decodeHex(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] =
          (byte)
              ((Character.digit(hex.charAt(2 * i), 16) << 4)
                  | Character.digit(hex.charAt(2 * i + 1), 16));
    }
    return out;
  }

  private static ChatlySignatureResult fail(String surface, Reason reason, String message) {
    return ChatlySignatureResult.refused(surface, reason, message);
  }

  private static String capitalise(String value) {
    return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  /** Clip an untrusted header value so an error message stays one line. */
  private static String preview(String value) {
    return value.length() > 48 ? value.substring(0, 47) + "…" : value;
  }
}
