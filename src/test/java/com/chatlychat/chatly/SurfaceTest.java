package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each surface method issues the verb and the <em>concrete</em> URL it should.
 *
 * <p>Sibling to {@link SurfaceLockTest}, which asks a different question of the same
 * {@link SurfaceCalls} list. That one normalises each path back to the manifest's template and
 * compares the set against {@code surface.json}; this one checks the fully-substituted URL, which
 * is the thing that goes on the wire.
 *
 * <p>Both matter, and neither covers the other. The lock would accept
 * {@code /v1/contacts/{id}/tags/{tag}} built from the wrong argument order — swap the id and the
 * tag and the template still normalises identically. This test would not: it compares against a
 * literal expected URL with each argument in its place.
 */
class SurfaceTest {

  @Test
  @DisplayName("every surface method issues the verb and concrete URL expected of it")
  void everyMethodIssuesTheRightVerbAndConcreteUrl() {
    Map<String, String> mismatches = new LinkedHashMap<>();

    for (SurfaceCalls.Entry entry : SurfaceCalls.all()) {
      SurfaceCalls.Issued issued;
      try {
        issued = SurfaceCalls.execute(entry);
      } catch (AssertionError | RuntimeException e) {
        mismatches.put(entry.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
        continue;
      }
      if (!entry.http().equals(issued.method())) {
        mismatches.put(entry.id(), "verb " + issued.method() + ", expected " + entry.http());
      } else if (!entry.concretePath().equals(issued.path())) {
        mismatches.put(
            entry.id(), "path " + issued.path() + ", expected " + entry.concretePath());
      }
    }

    if (!mismatches.isEmpty()) {
      StringBuilder sb = new StringBuilder(mismatches.size() + " surface method(s) are wrong:\n");
      mismatches.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
      fail(sb.toString());
    }
  }

  @Test
  void theSurfaceListHasNoDuplicateEntries() {
    Map<String, Integer> seen = new LinkedHashMap<>();
    for (SurfaceCalls.Entry entry : SurfaceCalls.all()) {
      seen.merge(entry.id(), 1, Integer::sum);
    }
    seen.forEach((id, count) -> assertEquals(1, count.intValue(), id + " is listed " + count + "x"));
  }

  /**
   * Every write sends an {@code Idempotency-Key}, and no read does.
   *
   * <p>Checked across the whole surface rather than on one method, because the header is attached by
   * verb down in the transport and a resource method that reached for the wrong verb helper would be
   * invisible to a single-method test.
   */
  @Test
  void everyWriteCarriesAnIdempotencyKeyAndNoReadDoes() {
    Map<String, String> wrong = new LinkedHashMap<>();

    for (SurfaceCalls.Entry entry : SurfaceCalls.all()) {
      FakeSender sender = new FakeSender();
      sender.then(entry.shape().status(), entry.shape().body());
      try {
        entry.invoke().apply(sender.clientNoRetries());
      } catch (RuntimeException e) {
        continue; // reported by the test above
      }
      boolean isWrite = !"GET".equals(entry.http());
      boolean hasKey = sender.lastRequest().header("Idempotency-Key").isPresent();
      if (isWrite && !hasKey) {
        wrong.put(entry.id(), "is a " + entry.http() + " and sent no Idempotency-Key");
      } else if (!isWrite && hasKey) {
        wrong.put(entry.id(), "is a GET and sent an Idempotency-Key");
      }
    }

    if (!wrong.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      wrong.forEach((k, v) -> sb.append("  ").append(k).append(' ').append(v).append('\n'));
      fail("idempotency mismatches:\n" + sb);
    }
  }

  /**
   * A control for {@link SurfaceCalls#normalise}: it must refuse a path whose literal segments do
   * not match the template.
   *
   * <p>Without this, the lock's whole guarantee rests on an untested helper — and the failure mode
   * is the quiet kind, where a typo'd literal gets rewritten into the manifest's text and the lock
   * agrees with a manifest the code does not implement. Which is the exact shape of the bug that
   * made the text-matching gate useless.
   */
  @Test
  void normaliseRefusesAMismatchedLiteralAndASegmentCountChange() {
    assertEquals(
        "/v1/contacts/{id}/tags/{tag}",
        SurfaceCalls.normalise("/v1/contacts/c_1/tags/vip", "/v1/contacts/{id}/tags/{tag}", "x"),
        "the happy path");

    org.junit.jupiter.api.Assertions.assertThrows(
        AssertionError.class,
        () -> SurfaceCalls.normalise("/v1/tickets/queue", "/v1/tickets/queues", "x"),
        "a literal segment off by one character must fail, not be rewritten");

    org.junit.jupiter.api.Assertions.assertThrows(
        AssertionError.class,
        () -> SurfaceCalls.normalise("/v1/contacts/c_1", "/v1/contacts/{id}/tags", "x"),
        "a different segment count must fail");

    org.junit.jupiter.api.Assertions.assertThrows(
        AssertionError.class,
        () -> SurfaceCalls.normalise("/v1/contacts//tags", "/v1/contacts/{id}/tags", "x"),
        "an empty segment where a placeholder was expected must fail");
  }
}
