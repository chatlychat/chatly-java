package com.chatlychat.chatly;

import java.time.Duration;
import java.util.Random;

/**
 * When and how long to wait before trying a failed request again.
 *
 * <p>The defaults implement the SDK contract's §4: three attempts, exponential backoff with jitter,
 * {@code Retry-After} honoured when the server sends it.
 *
 * <p><strong>What is retried, and why that list is short.</strong> Connection failures, {@code 429},
 * and {@code 5xx} — nothing else. A {@code 400}, {@code 404} or {@code 422} is a deterministic
 * refusal: retrying it burns the caller's latency budget three times over to receive the same answer,
 * and on a write it is worse than useless, because the server already has the request. A retried
 * write reuses the {@code Idempotency-Key} of the attempt that failed, which is the entire reason
 * that header exists.
 *
 * <p>Instances are immutable; the {@code with*} methods return copies.
 */
public final class RetryPolicy {

  /** Three attempts total: the first, plus two retries. */
  public static final int DEFAULT_MAX_RETRIES = 2;

  /** First backoff is drawn from [0, 500ms); it doubles from there. */
  public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);

  /** No single sleep exceeds this, however many attempts are configured. */
  public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(8);

  /**
   * Upper bound on a {@code Retry-After} we will actually sleep for.
   *
   * <p>The header is server-controlled, and a misconfigured limiter (or a proxy in front of one)
   * can send {@code Retry-After: 3600}. Blocking a caller's thread for an hour inside what looks
   * like a one-line SDK call is not a thing a library may do on its own authority, so a longer
   * value is treated as "stop retrying" and the {@code RateLimitException} is thrown with the
   * server's full advice intact on {@code retryAfter()} for the caller to schedule around.
   */
  public static final Duration DEFAULT_MAX_RETRY_AFTER = Duration.ofSeconds(60);

  private final int maxRetries;
  private final Duration initialBackoff;
  private final Duration maxBackoff;
  private final Duration maxRetryAfter;
  private final Random jitter;

  private RetryPolicy(
      int maxRetries,
      Duration initialBackoff,
      Duration maxBackoff,
      Duration maxRetryAfter,
      Random jitter) {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be >= 0");
    }
    if (initialBackoff.isNegative() || maxBackoff.isNegative()) {
      throw new IllegalArgumentException("backoff durations must not be negative");
    }
    this.maxRetries = maxRetries;
    this.initialBackoff = initialBackoff;
    this.maxBackoff = maxBackoff;
    this.maxRetryAfter = maxRetryAfter;
    this.jitter = jitter;
  }

  /** The contract's defaults. */
  public static RetryPolicy defaults() {
    return new RetryPolicy(
        DEFAULT_MAX_RETRIES,
        DEFAULT_INITIAL_BACKOFF,
        DEFAULT_MAX_BACKOFF,
        DEFAULT_MAX_RETRY_AFTER,
        new Random());
  }

  /** No retries at all: one attempt, then throw. */
  public static RetryPolicy none() {
    return new RetryPolicy(
        0, Duration.ZERO, Duration.ZERO, DEFAULT_MAX_RETRY_AFTER, new Random());
  }

  public RetryPolicy withMaxRetries(int value) {
    return new RetryPolicy(value, initialBackoff, maxBackoff, maxRetryAfter, jitter);
  }

  public RetryPolicy withInitialBackoff(Duration value) {
    return new RetryPolicy(maxRetries, value, maxBackoff, maxRetryAfter, jitter);
  }

  public RetryPolicy withMaxBackoff(Duration value) {
    return new RetryPolicy(maxRetries, initialBackoff, value, maxRetryAfter, jitter);
  }

  public RetryPolicy withMaxRetryAfter(Duration value) {
    return new RetryPolicy(maxRetries, initialBackoff, maxBackoff, value, jitter);
  }

  /**
   * Fix the jitter source.
   *
   * <p>For tests: a seeded {@link Random} makes the backoff sequence reproducible, and
   * {@code withInitialBackoff(Duration.ZERO)} makes a retry test finish in microseconds instead of
   * seconds. Both are used by this SDK's own suite — a retry test that really sleeps is a retry
   * test people delete.
   */
  public RetryPolicy withJitterSource(Random value) {
    return new RetryPolicy(maxRetries, initialBackoff, maxBackoff, maxRetryAfter, value);
  }

  public int maxRetries() {
    return maxRetries;
  }

  public Duration maxRetryAfter() {
    return maxRetryAfter;
  }

  /**
   * Whether a response with this status may be retried.
   *
   * <p>{@code 429} and {@code 5xx} only. Note {@code 408 Request Timeout} is deliberately excluded:
   * the Chatly API does not emit it, and a {@code 408} from an intermediary usually means the
   * request body was never fully received, which this SDK cannot distinguish from "received and
   * applied".
   */
  public boolean isRetryableStatus(int status) {
    return status == 429 || status >= 500;
  }

  /**
   * How long to wait before attempt number {@code attempt} (0-based: {@code 0} is the wait after the
   * first failure).
   *
   * <p><strong>Full jitter</strong>, i.e. a uniform draw from {@code [0, cap)} rather than
   * {@code cap/2 + rand(cap/2)}. With many clients retrying a shared 429, equal-jitter still leaves
   * a floor under every retry and the herd stays bunched; full jitter spreads it flat. This is the
   * AWS architecture blog's "Full Jitter" and it measurably wins on both completion time and total
   * work.
   *
   * @param serverAdvice the parsed {@code Retry-After}, or {@code null}. When present and within
   *     {@link #maxRetryAfter()}, it is used as-is and NOT jittered — the server named a time, and
   *     coming back early is how a limiter stays tripped.
   */
  public Duration backoffFor(int attempt, Duration serverAdvice) {
    if (serverAdvice != null && !serverAdvice.isNegative()) {
      if (serverAdvice.compareTo(maxRetryAfter) <= 0) {
        return serverAdvice;
      }
      // Longer than we are willing to block. Caller decides; see the field doc.
      return null;
    }
    long capMillis = initialBackoff.toMillis() << Math.min(attempt, 20);
    capMillis = Math.min(capMillis, maxBackoff.toMillis());
    if (capMillis <= 0) {
      return Duration.ZERO;
    }
    return Duration.ofMillis(jitter.nextLong(capMillis + 1));
  }
}
