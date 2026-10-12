package com.chatlychat.chatly.errors;

import java.util.Optional;

/**
 * Base type for every failure this SDK reports.
 *
 * <h2>Why unchecked</h2>
 *
 * This extends {@link RuntimeException}, not {@link Exception}. That is a deliberate choice and the
 * README records it too, because it is the kind of decision a reviewer should be able to challenge.
 *
 * <p>The deciding argument is {@code §5} of the SDK contract: every list endpoint must offer an
 * auto-paginating {@link java.util.Iterator} and a {@link java.util.stream.Stream} view. Both of
 * those fetch pages lazily from inside {@code Iterator.next()} / {@code hasNext()}, whose signatures
 * are fixed by the JDK and declare no checked exceptions. A checked {@code ChatlyException} could
 * therefore never escape a paginated walk — it would have to be wrapped in some unchecked carrier
 * on the way out, leaving callers with two error types for one failure and a {@code catch} that
 * silently misses half the cases. The same applies to any lambda passed to {@code map}, {@code
 * forEach}, {@code CompletableFuture.thenApply}, or a Spring {@code @Transactional} method
 * reference.
 *
 * <p>The cost is real: the compiler will not remind you that {@code contacts().list()} can fail. The
 * subtypes below are the compensation — they make the branches you actually care about
 * ({@code 401} vs {@code 403} vs {@code 429}) catchable by type instead of by string matching.
 *
 * <h2>What it carries</h2>
 *
 * Everything the API told us, including the bits the typed fields lose. {@link #requestId()} is the
 * one to quote in a support ticket; it is the {@code x-request-id} response header and it will find
 * the exact request in our logs.
 */
public class ChatlyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final String requestId;
  private final String body;

  /**
   * @param message human-readable message, from the API's {@code message} field where present
   * @param status HTTP status, or {@code 0} for a failure that never got a response
   * @param code the API's machine-readable {@code code} field, or {@code null}
   * @param requestId the {@code x-request-id} response header, or {@code null}
   * @param body the raw response body text, or {@code null}
   * @param cause the underlying exception, or {@code null}
   */
  public ChatlyException(
      String message, int status, String code, String requestId, String body, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.body = body;
  }

  /**
   * HTTP status code.
   *
   * @return the status, or {@code 0} when the request never produced an HTTP response at all (see
   *     {@link TransportException}). {@code 0} is used rather than {@code -1} or an
   *     {@code Optional} so that {@code if (e.status() >= 500)} is never accidentally true for a
   *     connection failure.
   */
  public int status() {
    return status;
  }

  /** The API's machine-readable error code, e.g. {@code contact_not_found}. */
  public Optional<String> code() {
    return Optional.ofNullable(code);
  }

  /** The {@code x-request-id} of the failed request. Quote this in a support ticket. */
  public Optional<String> requestId() {
    return Optional.ofNullable(requestId);
  }

  /**
   * The raw response body, exactly as received.
   *
   * <p>Present for anything the typed fields above lose — a {@code 422} carries a per-field
   * {@code details} array that has no accessor here, and reading it out of this string is the
   * supported way to get at it.
   */
  public Optional<String> body() {
    return Optional.ofNullable(body);
  }

  /**
   * Whether retrying this exact request could plausibly succeed.
   *
   * <p>True for connection failures, {@code 429} and {@code 5xx}; false for every other {@code 4xx}.
   * The SDK's own retry layer uses the same rule, so a {@code ChatlyException} that reaches you with
   * {@code retryable() == true} means the retries were already spent.
   */
  public boolean retryable() {
    return status == 429 || status >= 500 || status == 0;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder(getClass().getSimpleName());
    sb.append(": ").append(getMessage());
    if (status != 0) {
      sb.append(" [status=").append(status).append(']');
    }
    if (code != null) {
      sb.append(" [code=").append(code).append(']');
    }
    if (requestId != null) {
      sb.append(" [request_id=").append(requestId).append(']');
    }
    return sb.toString();
  }
}
