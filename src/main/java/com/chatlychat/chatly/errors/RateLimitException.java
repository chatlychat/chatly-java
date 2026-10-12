package com.chatlychat.chatly.errors;

import java.time.Duration;
import java.util.Optional;

/**
 * HTTP 429 — rate limited.
 *
 * <p>The SDK already retried this, honouring {@code Retry-After}, before giving up and throwing. If
 * you are seeing it, the limit outlasted the retry budget; widen {@code maxRetries} or slow the
 * caller down. {@link #retryAfter()} is the server's own advice on how long to wait.
 */
public final class RateLimitException extends ChatlyException {

  private static final long serialVersionUID = 1L;

  private final Duration retryAfter;

  public RateLimitException(
      String message,
      int status,
      String code,
      String requestId,
      String body,
      Duration retryAfter,
      Throwable cause) {
    super(message, status, code, requestId, body, cause);
    this.retryAfter = retryAfter;
  }

  /**
   * How long the server asked us to wait, parsed from the {@code Retry-After} response header.
   *
   * <p>Empty when the header was absent or unparseable. Both the delta-seconds form
   * ({@code Retry-After: 30}) and the HTTP-date form are parsed; the HTTP-date form is converted to
   * a duration from the moment the response was read, and clamped at zero so a clock skew cannot
   * hand you a negative sleep.
   */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
