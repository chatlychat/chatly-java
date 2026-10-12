package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chatlychat.chatly.errors.AuthenticationException;
import com.chatlychat.chatly.errors.ChatlyException;
import com.chatlychat.chatly.errors.ConflictException;
import com.chatlychat.chatly.errors.NotFoundException;
import com.chatlychat.chatly.errors.PermissionException;
import com.chatlychat.chatly.errors.RateLimitException;
import com.chatlychat.chatly.errors.ServerException;
import com.chatlychat.chatly.errors.TransportException;
import com.chatlychat.chatly.errors.ValidationException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CONTRACT.md §10.4 — a typed error per status, each carrying {@code request_id}.
 *
 * <p>Split out of {@link ClientContractTest} when that file crossed the repository's 650-line
 * ceiling. Errors are a responsibility of their own, which is the split the ceiling is there to
 * provoke.
 */
@DisplayName("§10.4 typed errors, each carrying request_id")
class ErrorTypesTest {

  private static final Map<String, List<String>> WITH_REQUEST_ID =
      Map.of("x-request-id", List.of("req_abc123"));

  @Test
  void notFound() {
    FakeSender sender =
        new FakeSender()
            .then(404, FakeSender.errorBody("not_found", "No such contact"), WITH_REQUEST_ID);

    NotFoundException e =
        assertThrows(
            NotFoundException.class, () -> sender.clientNoRetries().contacts().get("c_missing"));

    assertEquals(404, e.status());
    assertEquals("not_found", e.code().orElseThrow());
    assertEquals("No such contact", e.getMessage());
    assertEquals("req_abc123", e.requestId().orElseThrow());
    assertTrue(e.body().orElseThrow().contains("No such contact"));
    assertFalse(e.retryable());
  }

  /**
   * A 400 is a validation failure, and that is the common case.
   *
   * <p>Schema validation in this API is 400, not 422 — `ValidationError` is
   * `httpStatus: 400` and the exception filter maps a raw `ZodError` the same way. The transport
   * mapped 422 alone for a while, so the single most likely error a caller hits arrived as a bare
   * {@link ChatlyException} and the README's own {@code catch (ValidationException …)} could not
   * fire for it. Nothing asserted the mapping either way, which is why it survived.
   */
  @Test
  void aMalformedPayloadIsA400AndMapsToValidation() {
    FakeSender sender =
        new FakeSender()
            .then(
                400,
                FakeSender.errorBody("validation_failed", "Invalid request body"),
                WITH_REQUEST_ID);

    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> sender.clientNoRetries().contacts().upsert(Map.of("email", 7)));

    assertEquals(400, e.status());
    assertEquals("validation_failed", e.code().orElseThrow());
    assertEquals("req_abc123", e.requestId().orElseThrow());
    assertFalse(e.retryable());
  }

  @Test
  void validation() {
    FakeSender sender =
        new FakeSender()
            .then(
                422,
                FakeSender.errorBody("validation_failed", "body must be a string"),
                WITH_REQUEST_ID);

    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> sender.clientNoRetries().conversations().send("cv_1", "x"));

    assertEquals(422, e.status());
    assertEquals("validation_failed", e.code().orElseThrow());
    assertEquals("req_abc123", e.requestId().orElseThrow());
  }

  @Test
  void rateLimitCarriesRetryAfter() {
    FakeSender sender =
        new FakeSender()
            .then(
                429,
                FakeSender.errorBody("rate_limited", "Too many requests"),
                Map.of(
                    "retry-after", List.of("30"),
                    "x-request-id", List.of("req_abc123")));

    RateLimitException e =
        assertThrows(RateLimitException.class, () -> sender.clientNoRetries().whoami());

    assertEquals(429, e.status());
    assertEquals(Duration.ofSeconds(30), e.retryAfter().orElseThrow());
    assertEquals("req_abc123", e.requestId().orElseThrow());
    assertTrue(e.retryable());
  }

  @Test
  void rateLimitWithNoRetryAfterHeaderHasAnEmptyRetryAfter() {
    FakeSender sender = new FakeSender().then(429, FakeSender.errorBody("rate_limited", "slow"));
    RateLimitException e =
        assertThrows(RateLimitException.class, () -> sender.clientNoRetries().whoami());
    assertTrue(e.retryAfter().isEmpty(), "absent must be empty, not Duration.ZERO");
  }

  @Test
  void authenticationAndPermissionAreDistinguishable() {
    FakeSender unauth =
        new FakeSender().then(401, FakeSender.errorBody("unauthenticated", "bad key"));
    assertThrows(AuthenticationException.class, () -> unauth.clientNoRetries().whoami());

    FakeSender forbidden =
        new FakeSender().then(403, FakeSender.errorBody("forbidden", "missing scope"));
    assertThrows(PermissionException.class, () -> forbidden.clientNoRetries().whoami());
  }

  @Test
  void conflictCoversTheIdempotencyReplayMismatch() {
    FakeSender sender =
        new FakeSender()
            .then(409, FakeSender.errorBody("conflict", "Idempotency-Key reused with a new body"));
    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> sender.clientNoRetries().contacts().upsert(Map.of("email", "a@b.c")));
    assertEquals(409, e.status());
  }

  @Test
  void serverError() {
    FakeSender sender = new FakeSender().then(500, FakeSender.errorBody("unknown", "boom"));
    ServerException e = assertThrows(ServerException.class, () -> sender.clientNoRetries().whoami());
    assertEquals(500, e.status());
    assertTrue(e.retryable());
  }

  /**
   * The error envelope is nested under {@code error}.
   *
   * <p>Reading {@code code} at the top level is a bug this codebase has already shipped once — in
   * the widget, where it meant the session-recovery path never fired in production for a year. The
   * suite was green throughout, because its fixtures were hand-written top-level
   * {@code {code, message}} objects that agreed with the bug instead of with the server. So this
   * test asserts the nested shape explicitly, and {@link FakeSender#errorBody} is the only way the
   * suite builds one.
   */
  @Test
  void theNestedEnvelopeIsWhatIsRead() {
    FakeSender nested =
        new FakeSender().then(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"gone\"}}");
    NotFoundException e =
        assertThrows(NotFoundException.class, () -> nested.clientNoRetries().whoami());
    assertEquals("not_found", e.code().orElseThrow());
    assertEquals("gone", e.getMessage());
  }

  @Test
  void aTopLevelEnvelopeFromAnIntermediaryIsStillRead() {
    // A proxy's own JSON error, not from our exception filter. The fallback
    // must stay a fallback, but it must work.
    FakeSender flat = new FakeSender().then(502, "{\"code\":\"bad_gateway\",\"message\":\"nope\"}");
    ServerException e = assertThrows(ServerException.class, () -> flat.clientNoRetries().whoami());
    assertEquals("bad_gateway", e.code().orElseThrow());
  }

  @Test
  void aNonJsonErrorBodyDoesNotMaskTheStatus() {
    FakeSender html = new FakeSender().then(502, "<html><body>Bad Gateway</body></html>");
    ServerException e = assertThrows(ServerException.class, () -> html.clientNoRetries().whoami());
    assertEquals(502, e.status());
    assertTrue(e.code().isEmpty());
    assertTrue(e.body().orElseThrow().contains("Bad Gateway"));
  }

  @Test
  void aConnectionFailureIsItsOwnTypeWithStatusZero() {
    FakeSender sender = new FakeSender().thenIoError("Connection refused");
    TransportException e =
        assertThrows(TransportException.class, () -> sender.clientNoRetries().whoami());
    assertEquals(0, e.status());
    assertTrue(e.body().isEmpty());
    assertTrue(e.getMessage().contains("Connection refused"));
  }
}
