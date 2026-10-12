package com.chatlychat.chatly.errors;

/**
 * HTTP 409 — a conflict, including the idempotency-replay mismatch.
 *
 * <p>The replay case is the one to watch for: reusing an {@code Idempotency-Key} with a
 * <em>different</em> body is a 409, because the server will not quietly let a second, different
 * operation hide behind the first one's key. If you pinned a key with
 * {@link com.chatlychat.chatly.Chatly#withIdempotencyKey} and then changed the payload, this is why.
 */
public final class ConflictException extends ChatlyException {
  private static final long serialVersionUID = 1L;

  public ConflictException(
      String message, int status, String code, String requestId, String body, Throwable cause) {
    super(message, status, code, requestId, body, cause);
  }
}
