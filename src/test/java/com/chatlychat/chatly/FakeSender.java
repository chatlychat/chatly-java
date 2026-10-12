package com.chatlychat.chatly;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A scripted {@link Transport.Sender}: hand it the responses, read back the requests.
 *
 * <p>This is the seam that makes the whole suite network-free. It also records every request the SDK
 * built, which is the only way to assert on things that never come back in a response — that a write
 * carried an {@code Idempotency-Key}, that two writes carried different ones, that the URL was
 * assembled against the production host.
 *
 * <p>Deliberately NOT the only transport the suite uses: {@code LoopbackServerTest} runs the same
 * client against a real {@link com.sun.net.httpserver.HttpServer}. A fake sender validates the
 * request the SDK <em>intended</em>; only real HTTP validates the request it actually produced.
 */
final class FakeSender implements Transport.Sender {

  private final Deque<Object> scripted = new ArrayDeque<>();
  final List<Transport.Request> requests = new ArrayList<>();
  final List<String> bodies = new ArrayList<>();

  /** Queue a response. */
  FakeSender then(int status, String body) {
    scripted.add(Transport.Response.of(status, body));
    return this;
  }

  /** Queue a response with headers. */
  FakeSender then(int status, String body, Map<String, List<String>> headers) {
    scripted.add(new Transport.Response(status, headers, body));
    return this;
  }

  /** Queue a connection failure — what a DNS or connect error looks like to the transport. */
  FakeSender thenIoError(String message) {
    scripted.add(new IOException(message));
    return this;
  }

  int callCount() {
    return requests.size();
  }

  Transport.Request lastRequest() {
    return requests.get(requests.size() - 1);
  }

  /** The {@code Idempotency-Key} of the nth request (0-based), or null. */
  String idempotencyKeyOf(int index) {
    return requests.get(index).header("Idempotency-Key").orElse(null);
  }

  @Override
  public Transport.Response send(Transport.Request request, Duration timeout) throws IOException {
    requests.add(request);
    bodies.add(
        request.body() == null ? null : new String(request.body(), StandardCharsets.UTF_8));
    if (scripted.isEmpty()) {
      throw new AssertionError(
          "FakeSender ran out of scripted responses at call "
              + requests.size()
              + " ("
              + request.method()
              + " "
              + request.url()
              + "). The SDK made more requests than the test expected — which for a retry test is"
              + " the finding, not a setup problem.");
    }
    Object next = scripted.poll();
    if (next instanceof IOException io) {
      throw io;
    }
    return (Transport.Response) next;
  }

  /** A client wired to this sender, pointed at the default (production) base URL. */
  Chatly client() {
    return Chatly.builder()
        .apiKey("ck_test_key")
        .sender(this)
        // Zero backoff: a retry test that really sleeps is a retry test people
        // delete. The retry DECISIONS are what is under test, not Thread.sleep.
        .retryPolicy(
            RetryPolicy.defaults()
                .withInitialBackoff(Duration.ZERO)
                .withMaxBackoff(Duration.ZERO))
        .build();
  }

  /** A client wired to this sender with retries switched off. */
  Chatly clientNoRetries() {
    return Chatly.builder().apiKey("ck_test_key").sender(this).retryPolicy(RetryPolicy.none())
        .build();
  }

  /** The API's real error envelope: {@code {"error":{"code":…,"message":…}}}. */
  static String errorBody(String code, String message) {
    return Json.write(Map.of("error", Map.of("code", code, "message", message)));
  }
}
