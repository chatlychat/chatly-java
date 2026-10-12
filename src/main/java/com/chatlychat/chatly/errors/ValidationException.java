package com.chatlychat.chatly.errors;

/**
 * HTTP <strong>400</strong> or 422 — the request was well-formed but its content was rejected.
 *
 * <p>400 is the common one and the one to expect: schema validation in this API is 400
 * ({@code code: "validation_failed"}), both for a raw zod failure and for a thrown
 * {@code ValidationError}. A handful of routes use 422 for a request that parsed but could not be
 * carried out — a macro action that no longer exists, or one that failed mid-run, each naming its
 * position in the macro. Both are "you sent something I understood and will not accept", which is a
 * single branch for a caller, so they share a single type.
 *
 * <p>Per-field detail is in {@link ChatlyException#body()}; there is no typed accessor for it,
 * because the shape is per-route and inventing a stable one here would be a promise this SDK cannot
 * keep across API versions.
 */
public final class ValidationException extends ChatlyException {
  private static final long serialVersionUID = 1L;

  public ValidationException(
      String message, int status, String code, String requestId, String body, Throwable cause) {
    super(message, status, code, requestId, body, cause);
  }
}
