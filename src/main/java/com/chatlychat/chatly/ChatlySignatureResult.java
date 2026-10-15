package com.chatlychat.chatly;

/**
 * What {@link Webhooks#verifyChatlySignature} decided, and why.
 *
 * <p><strong>Why a reason and not a boolean.</strong> {@link Webhooks#verifySignature} returns
 * {@code false} for nine distinct failures, and an integrator debugging a rejected delivery cannot
 * tell "your clock is wrong" from "your secret is wrong" from "you passed the parsed body". Each of
 * those is a support ticket. {@link #reason()} is a stable code safe to branch on, identical in
 * every Chatly SDK, and {@link #message()} is a sentence written to be printed.
 *
 * <p>On {@code ok}: {@code reason} and {@code message} are null; {@code base} and {@code
 * secretIndex} are set; {@code timestampMs} is set except on {@code workflow_webhook_legacy}, which
 * sends no timestamp. On a refusal: {@code reason} and a non-empty {@code message} are set, and the
 * other three are null.
 *
 * @param ok whether the request is genuine and fresh
 * @param surface what actually verified — {@code "unified_v2"} when the v2 header was preferred over
 *     the surface asked for. On a refusal, the surface that refused; for {@code unknown_surface},
 *     the string the caller gave, which is why this is a String and not the enum
 * @param reason why it was refused, or null
 * @param message a sentence for whoever has to fix it, or null
 * @param timestampMs when the sender says it signed, in unix MILLISECONDS whatever unit the surface
 *     sends, so a seconds surface and a milliseconds surface are comparable
 * @param base which string the matching MAC was taken over
 * @param secretIndex index into the secret list THE CALLER PASSED that verified, or 0 for a single
 *     secret. During a rotation this is how you learn the old secret is no longer in use. Empty
 *     strings in the list are skipped but still counted, so {@code ["", s]} verifying with {@code s}
 *     reports 1
 */
public record ChatlySignatureResult(
    boolean ok,
    String surface,
    Reason reason,
    String message,
    Long timestampMs,
    Base base,
    Integer secretIndex) {

  /** Which string the MAC is taken over. */
  public enum Base {
    /** {@code <t>.<rawBody>} */
    BODY("body"),
    /** {@code <t>.<METHOD>.<path>.<rawBody>} — the unified base */
    TARGET("target"),
    /** {@code <rawBody>}, no timestamp anywhere — unbounded replay */
    BODY_ONLY("body_only");

    private final String code;

    Base(String code) {
      this.code = code;
    }

    /** The stable string, e.g. {@code "body_only"}. */
    public String code() {
      return code;
    }
  }

  /** Why a verification failed. The codes are stable strings, safe to branch on and to log. */
  public enum Reason {
    UNKNOWN_SURFACE("unknown_surface"),
    MISSING_SECRET("missing_secret"),
    MISSING_SIGNATURE_HEADER("missing_signature_header"),
    DUPLICATE_SIGNATURE_HEADER("duplicate_signature_header"),
    MALFORMED_SIGNATURE_HEADER("malformed_signature_header"),
    MISSING_TIMESTAMP("missing_timestamp"),
    MALFORMED_TIMESTAMP("malformed_timestamp"),
    TIMESTAMP_OUTSIDE_TOLERANCE("timestamp_outside_tolerance"),
    MISSING_REQUEST_TARGET("missing_request_target"),
    REPLAY_UNPROTECTED("replay_unprotected"),
    SIGNATURE_MISMATCH("signature_mismatch");

    private final String code;

    Reason(String code) {
      this.code = code;
    }

    /** The stable string, e.g. {@code "timestamp_outside_tolerance"}. */
    public String code() {
      return code;
    }
  }

  static ChatlySignatureResult accepted(
      String surface, Long timestampMs, Base base, int secretIndex) {
    return new ChatlySignatureResult(true, surface, null, null, timestampMs, base, secretIndex);
  }

  static ChatlySignatureResult refused(String surface, Reason reason, String message) {
    return new ChatlySignatureResult(false, surface, reason, message, null, null, null);
  }

  /** {@link #reason()}'s stable string, or null on {@code ok}. */
  public String reasonCode() {
    return reason == null ? null : reason.code();
  }
}
