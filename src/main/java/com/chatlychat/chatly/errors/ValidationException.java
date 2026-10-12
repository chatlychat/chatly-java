package com.chatlychat.chatly.errors;

/**
 * HTTP 422 — the request was well-formed but the payload failed the API's schema.
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
