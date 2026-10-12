package com.chatlychat.chatly.errors;

/**
 * The request never produced an HTTP response: DNS failure, connection refused, TLS failure, read
 * timeout, or the calling thread being interrupted.
 *
 * <p>Its own type, separate from every HTTP status, because the contract requires that distinction
 * and because the question it answers is different: an HTTP error means the API considered your
 * request and declined it, while this means the API may never have heard of it. {@link
 * ChatlyException#status()} is {@code 0} here, and {@link ChatlyException#body()} is empty.
 *
 * <p><strong>A write that fails this way may still have been applied.</strong> The connection can
 * drop after the server committed and before the response arrives. That is exactly what the
 * {@code Idempotency-Key} header is for: retry the same logical write through
 * {@link com.chatlychat.chatly.Chatly#withIdempotencyKey} and the server will replay its first
 * answer rather than acting twice. The SDK's internal retries already do this.
 */
public final class TransportException extends ChatlyException {
  private static final long serialVersionUID = 1L;

  public TransportException(String message, Throwable cause) {
    super(message, 0, null, null, null, cause);
  }
}
