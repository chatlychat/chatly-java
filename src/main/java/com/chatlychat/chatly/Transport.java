package com.chatlychat.chatly;

import com.chatlychat.chatly.errors.AuthenticationException;
import com.chatlychat.chatly.errors.ChatlyException;
import com.chatlychat.chatly.errors.ConflictException;
import com.chatlychat.chatly.errors.NotFoundException;
import com.chatlychat.chatly.errors.PermissionException;
import com.chatlychat.chatly.errors.RateLimitException;
import com.chatlychat.chatly.errors.ServerException;
import com.chatlychat.chatly.errors.TransportException;
import com.chatlychat.chatly.errors.ValidationException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything between a resource method and the wire: URL assembly, auth, idempotency, retries,
 * status-to-exception mapping, and JSON decoding.
 *
 * <p>Resource classes know a verb and a path template and nothing else. All the behaviour the SDK
 * contract specifies lives here, once, so that there is exactly one place where a retry can be got
 * wrong.
 *
 * <p>Instances are immutable and thread-safe, as is the {@link HttpClient} underneath. One
 * {@link Chatly} per process is the intended usage.
 */
public final class Transport {

  /** A request about to go out. Exposed so an injected {@link Sender} can inspect it. */
  public record Request(
      String method, String url, Map<String, List<String>> headers, byte[] body) {

    /** First value of a header, case-insensitively. */
    public Optional<String> header(String name) {
      return firstHeader(headers, name);
    }
  }

  /** A response that came back. {@code body} is the raw text, never re-serialised. */
  public record Response(int status, Map<String, List<String>> headers, String body) {

    /** First value of a header, case-insensitively. */
    public Optional<String> header(String name) {
      return firstHeader(headers, name);
    }

    /** Convenience for a test fixture: a response with no headers. */
    public static Response of(int status, String body) {
      return new Response(status, Map.of(), body);
    }
  }

  /**
   * The one seam tests use instead of a network.
   *
   * <p>A {@code Sender} receives a fully-formed {@link Request} — auth header, user agent and
   * idempotency key already attached — and returns a {@link Response}. Injecting one lets a test
   * assert on exactly what the SDK would have put on the wire, and lets it return a {@code 429}
   * followed by a {@code 200} without a server. Throwing {@link IOException} from it is how a test
   * simulates a connection failure.
   *
   * <p>This SDK's own suite uses both this and a real loopback {@link
   * com.sun.net.httpserver.HttpServer}, because they catch different things: the injected sender
   * sees the intended request, the loopback server sees the request HTTP actually produced. A URL
   * built wrong is invisible to the first and obvious to the second.
   */
  @FunctionalInterface
  public interface Sender {
    Response send(Request request, Duration timeout) throws IOException, InterruptedException;
  }

  private final String baseUrl;
  private final String apiKey;
  private final String userAgent;
  private final Duration timeout;
  private final RetryPolicy retryPolicy;
  private final Sender sender;
  private final String pinnedIdempotencyKey;

  Transport(
      String baseUrl,
      String apiKey,
      String userAgent,
      Duration timeout,
      RetryPolicy retryPolicy,
      Sender sender,
      String pinnedIdempotencyKey) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.userAgent = userAgent;
    this.timeout = timeout;
    this.retryPolicy = retryPolicy;
    this.sender = sender;
    this.pinnedIdempotencyKey = pinnedIdempotencyKey;
  }

  /** A copy of this transport with a pinned {@code Idempotency-Key}. */
  Transport withPinnedIdempotencyKey(String key) {
    return new Transport(baseUrl, apiKey, userAgent, timeout, retryPolicy, sender, key);
  }

  String baseUrl() {
    return baseUrl;
  }

  RetryPolicy retryPolicy() {
    return retryPolicy;
  }

  // ------------------------------------------------------------------ verbs

  /** GET. Never carries an idempotency key — a read cannot double-write. */
  public Object get(String path, Query query) {
    return execute("GET", path, query, null, false);
  }

  /** POST with a JSON body. Carries an idempotency key. */
  public Object post(String path, Query query, Object body) {
    return execute("POST", path, query, body, true);
  }

  /** PATCH with a JSON body. Carries an idempotency key. */
  public Object patch(String path, Query query, Object body) {
    return execute("PATCH", path, query, body, true);
  }

  /** PUT with a JSON body. Carries an idempotency key. */
  public Object put(String path, Query query, Object body) {
    return execute("PUT", path, query, body, true);
  }

  /**
   * DELETE. Carries an idempotency key.
   *
   * <p>A body is allowed because the contract treats DELETE as an ordinary write and one route
   * ({@code DELETE /v1/contacts/{id}/tags/{tag}}) could grow one; pass {@code null} for none, and no
   * {@code Content-Type} is sent in that case.
   */
  public Object delete(String path, Query query, Object body) {
    return execute("DELETE", path, query, body, true);
  }

  // ---------------------------------------------------------------- the loop

  /**
   * Send one logical request, retrying per {@link RetryPolicy}.
   *
   * <p>The {@code Idempotency-Key} is minted <strong>once, before the loop</strong>, and every
   * attempt reuses it. Minting one per attempt would turn a single retried write into two or three
   * distinct operations upstream, which is the exact failure the header exists to prevent — and it
   * is an easy thing to get wrong by putting the mint inside the loop body, so note that the
   * statement below is deliberately outside it.
   */
  private Object execute(String method, String path, Query query, Object body, boolean isWrite) {
    String url = buildUrl(path, query);
    byte[] encodedBody = body == null ? null : Json.write(body).getBytes(StandardCharsets.UTF_8);
    String idempotencyKey = isWrite ? nextIdempotencyKey() : null;

    int attempt = 0;
    while (true) {
      Request request = buildRequest(method, url, encodedBody, idempotencyKey);
      Response response;
      try {
        response = sender.send(request, timeout);
      } catch (IOException io) {
        if (attempt < retryPolicy.maxRetries()) {
          if (sleepBefore(attempt, null)) {
            attempt++;
            continue;
          }
        }
        throw new TransportException(
            method + " " + url + " failed before any response was received: " + io.getMessage(),
            io);
      } catch (InterruptedException interrupted) {
        // Re-assert the flag. Swallowing it leaves a thread that nothing can
        // ever shut down, and a pool that will not drain on shutdown.
        Thread.currentThread().interrupt();
        throw new TransportException(method + " " + url + " was interrupted", interrupted);
      }

      if (response.status() >= 200 && response.status() < 300) {
        return decodeSuccess(response);
      }

      ChatlyException failure = toException(method, url, response);
      boolean canRetry =
          retryPolicy.isRetryableStatus(response.status()) && attempt < retryPolicy.maxRetries();
      if (!canRetry) {
        throw failure;
      }
      Duration advice =
          failure instanceof RateLimitException rl ? rl.retryAfter().orElse(null) : null;
      if (!sleepBefore(attempt, advice)) {
        // The policy declined to wait (a Retry-After longer than we may block
        // a caller's thread for). The caller gets the error, with the server's
        // own advice intact on it.
        throw failure;
      }
      attempt++;
    }
  }

  /**
   * @return false when the policy says not to wait at all, which means "stop retrying"
   */
  private boolean sleepBefore(int attempt, Duration serverAdvice) {
    Duration wait = retryPolicy.backoffFor(attempt, serverAdvice);
    if (wait == null) {
      return false;
    }
    if (wait.isZero() || wait.isNegative()) {
      return true;
    }
    try {
      Thread.sleep(wait.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransportException("interrupted while backing off before a retry", e);
    }
  }

  private String nextIdempotencyKey() {
    if (pinnedIdempotencyKey != null) {
      return pinnedIdempotencyKey;
    }
    // A v4 UUID. The API accepts a UUID of version 1-7 or a 16-128 character
    // opaque token; this is comfortably both.
    return UUID.randomUUID().toString();
  }

  private Request buildRequest(String method, String url, byte[] body, String idempotencyKey) {
    var headers = new java.util.LinkedHashMap<String, List<String>>();
    headers.put("Authorization", List.of("Bearer " + apiKey));
    headers.put("Accept", List.of("application/json"));
    headers.put("User-Agent", List.of(userAgent));
    if (body != null) {
      headers.put("Content-Type", List.of("application/json"));
    }
    if (idempotencyKey != null) {
      headers.put("Idempotency-Key", List.of(idempotencyKey));
    }
    return new Request(method, url, Map.copyOf(headers), body);
  }

  String buildUrl(String path, Query query) {
    StringBuilder sb = new StringBuilder(baseUrl);
    if (!path.startsWith("/")) {
      sb.append('/');
    }
    sb.append(path);
    if (query != null && !query.isEmpty()) {
      sb.append('?').append(query.encode());
    }
    return sb.toString();
  }

  private Object decodeSuccess(Response response) {
    if (response.status() == 204) {
      return null;
    }
    String text = response.body();
    if (text == null || text.isBlank()) {
      // A 200 with an empty body. Returning null rather than throwing: a few
      // write routes answer 200 with nothing, and a parse error here would be
      // reported as a broken API when the request in fact succeeded.
      return null;
    }
    try {
      return Json.parse(text);
    } catch (Json.JsonParseException bad) {
      throw new ChatlyException(
          "the API returned a "
              + response.status()
              + " whose body is not valid JSON: "
              + bad.getMessage(),
          response.status(),
          null,
          response.header("x-request-id").orElse(null),
          text,
          bad);
    }
  }

  // ------------------------------------------------------- status -> typed error

  private ChatlyException toException(String method, String url, Response response) {
    int status = response.status();
    String requestId = response.header("x-request-id").orElse(null);
    String raw = response.body();

    String code = null;
    String message = null;
    if (raw != null && !raw.isBlank()) {
      try {
        Object parsed = Json.parse(raw);
        if (parsed instanceof Map<?, ?> top) {
          // The API nests its error body under `error`:
          //   {"error":{"code":"forbidden","message":"…","details":{…}}}
          // Reading `code` at the TOP level is a real bug this codebase has
          // already shipped once, in the widget, where it meant the
          // session-recovery path never fired in production. The top-level
          // fallback below is for responses that do NOT come from our own
          // exception filter — an edge proxy's 502, say — and must stay a
          // fallback, not the first choice.
          Object nested = top.get("error");
          if (nested instanceof Map<?, ?> err) {
            code = asString(err.get("code"));
            message = asString(err.get("message"));
          }
          if (code == null) {
            code = asString(top.get("code"));
          }
          if (message == null) {
            message = asString(top.get("message"));
          }
        }
      } catch (Json.JsonParseException ignored) {
        // A non-JSON error body (an HTML 502 page from a proxy, for instance).
        // `raw` is kept on the exception, which is where it belongs.
        code = null;
      }
    }

    String detail = message != null ? message : (status + " from " + method + " " + url);

    return switch (status) {
      case 401 -> new AuthenticationException(detail, status, code, requestId, raw, null);
      case 403 -> new PermissionException(detail, status, code, requestId, raw, null);
      case 404 -> new NotFoundException(detail, status, code, requestId, raw, null);
      case 409 -> new ConflictException(detail, status, code, requestId, raw, null);
      /*
       * 400 AND 422. Both are real; the interesting one is 400.
       *
       * This mapped 422 alone, which was the bug: SCHEMA validation in this API
       * is 400, not 422. `ValidationError` in `packages/shared/src/errors` is
       * `httpStatus: 400, code: validation_failed`, and the exception filter
       * maps a raw `ZodError` the same way. So the common case — a malformed
       * payload — arrived as a bare `ChatlyException` and the README's own
       * `catch (ValidationException …)` could never fire for it.
       *
       * 422 is NOT dead code, and a comment here previously said it was.
       * `apps/api/src/modules/macros/macro-action-runner.ts` throws 422 twice
       * (an action removed from the registry, and an action that failed at
       * run time, each naming the action's index), and
       * `modules/hired-agents/hired-agents.service.ts` twice more. The macros
       * ones are reachable with an API key, so an SDK caller can see a 422
       * today. Both statuses mean "well-formed request, rejected on content",
       * which is one branch for a caller, so they share one type.
       */
      case 400, 422 -> new ValidationException(detail, status, code, requestId, raw, null);
      case 429 ->
          new RateLimitException(
              detail, status, code, requestId, raw, parseRetryAfter(response), null);
      default -> {
        if (status >= 500) {
          yield new ServerException(detail, status, code, requestId, raw, null);
        }
        yield new ChatlyException(detail, status, code, requestId, raw, null);
      }
    };
  }

  /**
   * Parse {@code Retry-After}, in both forms RFC 9110 permits.
   *
   * <p>Delta-seconds ({@code Retry-After: 30}) is what our limiter sends. The HTTP-date form is
   * parsed too, because an intermediary may rewrite it, and is converted relative to <em>now</em>
   * and clamped at zero — a client clock running fast would otherwise produce a negative duration
   * and a caller computing {@code sleep(retryAfter)} would get an exception instead of a wait.
   *
   * @return null when the header is absent or unparseable
   */
  private static Duration parseRetryAfter(Response response) {
    Optional<String> header = response.header("retry-after");
    if (header.isEmpty()) {
      return null;
    }
    String value = header.get().trim();
    if (value.isEmpty()) {
      return null;
    }
    try {
      long seconds = Long.parseLong(value);
      return seconds < 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
    } catch (NumberFormatException notANumber) {
      // fall through to the date form
    }
    try {
      ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
      Duration until = Duration.between(ZonedDateTime.now(when.getZone()), when);
      return until.isNegative() ? Duration.ZERO : until;
    } catch (DateTimeParseException notADate) {
      return null;
    }
  }

  private static String asString(Object v) {
    return v instanceof String s && !s.isEmpty() ? s : null;
  }

  static Optional<String> firstHeader(Map<String, List<String>> headers, String name) {
    if (headers == null) {
      return Optional.empty();
    }
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
        List<String> values = e.getValue();
        if (values != null && !values.isEmpty()) {
          return Optional.ofNullable(values.get(0));
        }
      }
    }
    return Optional.empty();
  }

  // ------------------------------------------------------- the default sender

  /**
   * A {@link Sender} backed by {@link HttpClient}.
   *
   * <p>Separate from {@link Transport} so that the retry/error/idempotency logic above is tested
   * without any HTTP at all, and so a customer can supply their own {@code HttpClient} (with a
   * proxy, a custom {@code SSLContext}, or an executor they control) without reimplementing any of
   * it.
   */
  static final class JdkSender implements Sender {

    private final HttpClient client;

    JdkSender(HttpClient client) {
      this.client = client;
    }

    @Override
    public Response send(Request request, Duration timeout)
        throws IOException, InterruptedException {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(request.url())).timeout(timeout);

      HttpRequest.BodyPublisher publisher =
          request.body() == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(request.body());
      builder.method(request.method(), publisher);

      request
          .headers()
          .forEach((name, values) -> values.forEach(value -> builder.header(name, value)));

      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Response(response.statusCode(), response.headers().map(), response.body());
    }
  }

  /** The {@link HttpClient} used when the caller does not supply one. */
  static HttpClient defaultHttpClient(Duration connectTimeout) {
    return HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        // NEVER: a redirect would re-send the Authorization header to whatever
        // host the Location pointed at, which hands a workspace-wide bearer
        // credential to a third party. The API does not redirect; if it ever
        // appears to, that is the bug to investigate, not a setting to relax.
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  static String defaultUserAgent(String version) {
    return "chatly-sdk-java/" + version;
  }

  static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.ROOT);
  }
}
