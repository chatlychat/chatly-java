package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chatlychat.chatly.errors.NotFoundException;
import com.chatlychat.chatly.errors.TransportException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The same client, over real HTTP, against a loopback server from the JDK.
 *
 * <p>Why both this and {@link FakeSender}: a fake sender validates the request the SDK
 * <em>intended</em> to make. Only real HTTP validates the request it actually produced — a URL
 * assembled with a missing slash, a header the JDK client refuses to set, a method the client will
 * not send a body with. Those are invisible to an injected transport and obvious here.
 *
 * <p>Binds to {@code 127.0.0.1} on an ephemeral port. No external network, no API key, no running
 * API; this runs on an air-gapped machine.
 */
class LoopbackServerTest {

  private HttpServer server;
  private String baseUrl;
  private final List<Recorded> received = Collections.synchronizedList(new ArrayList<>());

  /** One request as the server saw it — the real wire, not the SDK's intent. */
  record Recorded(String method, String uri, Map<String, List<String>> headers, String body) {}

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private Chatly client() {
    return Chatly.builder()
        .apiKey("ck_loopback")
        .baseUrl(baseUrl)
        .timeout(Duration.ofSeconds(5))
        .retryPolicy(
            RetryPolicy.defaults()
                .withInitialBackoff(Duration.ZERO)
                .withMaxBackoff(Duration.ZERO))
        .build();
  }

  /** Respond with a fixed status and body, recording what arrived. */
  private void handle(String path, int status, String body) {
    handle(path, exchange -> respond(exchange, status, body, Map.of()));
  }

  private void handle(String path, Handler handler) {
    server.createContext(
        path,
        exchange -> {
          String requestBody;
          try (InputStream in = exchange.getRequestBody()) {
            requestBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          received.add(
              new Recorded(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().toString(),
                  Map.copyOf(exchange.getRequestHeaders()),
                  requestBody));
          handler.handle(exchange);
        });
  }

  @FunctionalInterface
  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }

  private static void respond(
      HttpExchange exchange, int status, String body, Map<String, String> headers)
      throws IOException {
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("content-type", "application/json");
    headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
    if (status == 204) {
      exchange.sendResponseHeaders(204, -1);
    } else {
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  @Test
  void aRealGetCarriesTheAuthHeaderAndParsesTheBody() {
    handle("/v1/api-keys/whoami", 200, "{\"workspaceId\":\"ws_1\",\"scopes\":[\"contacts:read\"]}");

    Map<String, Object> me = client().whoami();

    assertEquals("ws_1", me.get("workspaceId"));
    assertEquals(List.of("contacts:read"), me.get("scopes"));

    Recorded req = received.get(0);
    assertEquals("GET", req.method());
    assertEquals("/v1/api-keys/whoami", req.uri());
    assertEquals("Bearer ck_loopback", header(req, "Authorization"));
    assertEquals("application/json", header(req, "Accept"));
    assertEquals("chatly-sdk-java/0.1.0", header(req, "User-Agent"));
  }

  @Test
  void aRealPostSendsJsonAndAnIdempotencyKey() {
    handle("/v1/contacts", 201, "{\"id\":\"c_1\",\"email\":\"jamie@acme.io\"}");

    // A LinkedHashMap, not Map.of: the codec serialises a map in ITS iteration
    // order, and Map.of's order is deliberately randomised per JVM run. This
    // assertion failed on the first run for exactly that reason. The wire order
    // of JSON object keys is semantically irrelevant to the server, so this is
    // a test-only concern -- but it is a real one for anyone asserting on a
    // request body, and it is documented in the README for that reason.
    Map<String, Object> input = new java.util.LinkedHashMap<>();
    input.put("externalId", "u-42");
    input.put("email", "jamie@acme.io");

    Map<String, Object> contact = client().contacts().upsert(input);

    assertEquals("c_1", contact.get("id"));

    Recorded req = received.get(0);
    assertEquals("POST", req.method());
    assertEquals("application/json", header(req, "Content-Type"));
    assertEquals("{\"externalId\":\"u-42\",\"email\":\"jamie@acme.io\"}", req.body());

    String key = header(req, "Idempotency-Key");
    assertTrue(key != null && key.length() >= 16, "Idempotency-Key must survive real HTTP: " + key);
  }

  /**
   * A real PATCH, PUT and DELETE with a body.
   *
   * <p>Worth its own test: {@code HttpRequest.Builder.method()} with a body publisher is the only
   * way to send one on a DELETE through the JDK client, and getting that wrong is invisible to an
   * injected sender because the {@code Request} record looks identical either way.
   */
  @Test
  void patchPutAndDeleteAllReachTheServerWithTheirBodies() {
    handle("/v1/macros/m_1", exchange -> respond(exchange, 200, "{\"id\":\"m_1\"}", Map.of()));
    handle(
        "/v1/conversations/cv_1/agent-handling",
        exchange -> respond(exchange, 200, "{\"state\":\"agent\"}", Map.of()));

    Chatly chatly = client();
    chatly.macros().update("m_1", Map.of("name", "renamed"));
    chatly.conversations().setAgentHandling("cv_1", "agent");

    assertEquals("PATCH", received.get(0).method());
    assertEquals("{\"name\":\"renamed\"}", received.get(0).body());
    assertEquals("PUT", received.get(1).method());
    assertEquals("{\"state\":\"agent\"}", received.get(1).body());
  }

  @Test
  void a204DeleteReturnsCleanlyWithNoBodyToParse() {
    handle("/v1/macros/m_1", exchange -> respond(exchange, 204, null, Map.of()));

    client().macros().delete("m_1");

    assertEquals("DELETE", received.get(0).method());
  }

  @Test
  void queryParametersSurviveRealEncoding() {
    handle("/v1/search", 200, "{\"items\":[],\"nextCursor\":null}");

    client()
        .search()
        .query("needs follow up", Query.of().add("kinds", "kb").add("kinds", "ticket"));

    String uri = received.get(0).uri();
    assertTrue(uri.startsWith("/v1/search?"), uri);
    assertTrue(uri.contains("q=needs+follow+up"), uri);
    assertTrue(uri.contains("kinds=kb"), uri);
    assertTrue(uri.contains("kinds=ticket"), uri);
  }

  @Test
  void aPercentEncodedPathSegmentResolvesToTheRightRoute() {
    handle("/v1/contacts/c_1/tags/", 200, "{\"id\":\"c_1\"}");

    client().contacts().removeTag("c_1", "needs follow up");

    assertEquals("/v1/contacts/c_1/tags/needs%20follow%20up", received.get(0).uri());
  }

  @Test
  void aRealErrorResponseBecomesATypedExceptionWithTheRequestId() {
    handle(
        "/v1/contacts/c_missing",
        exchange ->
            respond(
                exchange,
                404,
                FakeSender.errorBody("not_found", "No such contact"),
                Map.of("x-request-id", "req_loopback")));

    NotFoundException e = assertThrows(NotFoundException.class, () -> client().contacts().get("c_missing"));

    assertEquals(404, e.status());
    assertEquals("not_found", e.code().orElseThrow());
    assertEquals("req_loopback", e.requestId().orElseThrow());
  }

  @Test
  void aRealRetryReusesTheIdempotencyKeyAcrossTwoSeparateHttpRequests() {
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    handle(
        "/v1/cdp/events",
        exchange -> {
          if (calls.getAndIncrement() == 0) {
            respond(exchange, 500, FakeSender.errorBody("unknown", "boom"), Map.of());
          } else {
            respond(exchange, 201, "{\"id\":\"ev_1\"}", Map.of());
          }
        });

    client().events().track("c_1", "checkout.completed", Map.of("orderId", "O-7"));

    assertEquals(2, received.size());
    assertEquals(
        header(received.get(0), "Idempotency-Key"),
        header(received.get(1), "Idempotency-Key"),
        "the retry must carry the key of the attempt that failed");
    assertEquals(received.get(0).body(), received.get(1).body());
  }

  @Test
  void twoRealWritesCarryDifferentKeys() {
    handle("/v1/cdp/events", 201, "{\"id\":\"ev\"}");

    Chatly chatly = client();
    chatly.events().track("c_1", "a");
    chatly.events().track("c_1", "b");

    assertNotEquals(
        header(received.get(0), "Idempotency-Key"), header(received.get(1), "Idempotency-Key"));
  }

  @Test
  void autoPaginationWalksRealResponses() {
    handle(
        "/v1/contacts",
        exchange -> {
          String uri = exchange.getRequestURI().toString();
          if (uri.contains("cursor=cur_2")) {
            respond(exchange, 200, "{\"items\":[{\"id\":\"c_2\"}],\"nextCursor\":null}", Map.of());
          } else {
            respond(
                exchange, 200, "{\"items\":[{\"id\":\"c_1\"}],\"nextCursor\":\"cur_2\"}", Map.of());
          }
        });

    List<String> ids =
        client().contacts().listAuto().stream().map(c -> (String) c.get("id")).toList();

    assertEquals(List.of("c_1", "c_2"), ids);
    assertEquals(2, received.size());
  }

  @Test
  void aRealTimeoutIsATransportExceptionNotAnHttpError() {
    handle(
        "/v1/api-keys/whoami",
        exchange -> {
          try {
            Thread.sleep(1500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          respond(exchange, 200, "{}", Map.of());
        });

    Chatly impatient =
        Chatly.builder()
            .apiKey("ck_loopback")
            .baseUrl(baseUrl)
            .timeout(Duration.ofMillis(150))
            .retryPolicy(RetryPolicy.none())
            .build();

    TransportException e = assertThrows(TransportException.class, impatient::whoami);
    assertEquals(0, e.status(), "a timeout has no HTTP status");
  }

  @Test
  void aConnectionRefusedIsATransportException() {
    int closedPort = server.getAddress().getPort();
    server.stop(0);
    server = null;

    Chatly chatly =
        Chatly.builder()
            .apiKey("ck_loopback")
            .baseUrl("http://127.0.0.1:" + closedPort)
            .retryPolicy(RetryPolicy.none())
            .build();

    assertThrows(TransportException.class, chatly::whoami);
  }

  @Test
  void anArrayRouteDecodesOverRealHttp() {
    handle("/v1/labels", 200, "[{\"id\":\"l_1\",\"name\":\"urgent\"}]");

    List<Map<String, Object>> labels = client().workspace().labels();

    assertEquals(1, labels.size());
    assertEquals("urgent", labels.get(0).get("name"));
  }

  @Test
  void aUtf8ResponseBodyDecodesCorrectly() {
    handle("/v1/contacts/c_1", 200, "{\"name\":\"José 🙂\"}");
    assertEquals("José 🙂", client().contacts().get("c_1").get("name"));
  }

  @Test
  void aUtf8RequestBodyIsSentAsUtf8() {
    handle("/v1/conversations/cv_1/messages", 201, "{\"id\":\"m_1\"}");

    client().conversations().send("cv_1", "Café ☕ 🙂");

    assertEquals("{\"body\":\"Café ☕ 🙂\"}", received.get(0).body());
  }

  private static String header(Recorded req, String name) {
    for (Map.Entry<String, List<String>> e : req.headers().entrySet()) {
      if (e.getKey().equalsIgnoreCase(name)) {
        return e.getValue().get(0);
      }
    }
    return null;
  }
}
