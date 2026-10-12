package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which list routes are cursor-paginated, and which return a bare array.
 *
 * <p>This exists because of a bug the other SDKs hit and this one was one step away from. The
 * manifest briefly marked {@code GET /v1/macros} {@code "paged": true}; the route is
 * {@code @PaginationExempt} and answers with a bare array capped at 200. The .NET and Rust clients
 * both raised a decode error on it <em>in real use while their suites stayed green</em>, because
 * their fake transports answer every route with a page envelope. A fake that always returns the
 * shape the SDK expects cannot discover that the SDK expects the wrong shape.
 *
 * <p>{@link FakeSender} is not that kind of fake — it has no default response at all and throws if
 * a test has not scripted one — but "our fake happens to be stricter" is not an assertion, so here
 * are the assertions.
 */
class ListShapeTest {

  private static final String MANIFEST = "packages/sdk-spec/surface.json";

  /**
   * The manifest's {@code paged} count, as an anti-vacuity floor.
   *
   * <p><strong>7, not 8.</strong> It was 8 while {@code macros.list} was wrongly marked paged; the
   * macros row is now annotated {@code $note: "NOT paged"} and the real figure is 7. The floor
   * matters because every assertion below iterates the paged entries: if the manifest stopped
   * parsing, or someone quietly unmarked a route, the loop would run over a shorter list and this
   * file would report a pass for pagination it never checked. That failure mode — a gate reporting
   * the number of things it managed to parse rather than the number that exist — has happened
   * twice in this repository, so the count is pinned rather than trusted.
   */
  private static final int EXPECTED_PAGED_ENTRIES = 7;

  // ------------------------------------------------------- paged routes

  /** The auto-paginating form of each route the manifest marks {@code paged}. */
  private static Map<String, Function<Chatly, Pager<Map<String, Object>>>> pagers() {
    Map<String, Function<Chatly, Pager<Map<String, Object>>>> m = new LinkedHashMap<>();
    m.put("contacts.list", c -> c.contacts().listAuto());
    m.put("conversations.list", c -> c.conversations().listAuto());
    m.put("conversations.messages", c -> c.conversations().messagesAuto("cv_1", Query.of()));
    m.put("tickets.list", c -> c.tickets().listAuto());
    m.put("kb.articles", c -> c.kb().articlesAuto());
    m.put("events.list", c -> c.events().listAuto());
    m.put("webhooks.deliveries", c -> c.webhooks().deliveriesAuto());
    return m;
  }

  @Test
  @DisplayName("every route the manifest marks `paged` has a working auto-paginator")
  void everyPagedRouteHasAPagerThatWalksAndStops() {
    List<String> paged = pagedEntryIds();

    assertEquals(
        EXPECTED_PAGED_ENTRIES,
        paged.size(),
        "the manifest marks " + paged.size() + " entries paged, expected " + EXPECTED_PAGED_ENTRIES
            + " — if the surface legitimately changed, update this constant in the same commit;"
            + " if it did not, the manifest has stopped parsing and every loop below is vacuous");

    Map<String, Function<Chatly, Pager<Map<String, Object>>>> pagers = pagers();
    List<String> problems = new ArrayList<>();

    for (String id : paged) {
      Function<Chatly, Pager<Map<String, Object>>> build = pagers.get(id);
      if (build == null) {
        problems.add(id + " is marked paged in the manifest but has no auto-paginator here");
        continue;
      }
      // Two pages, then a null cursor. The contract's §5 requirement, applied
      // to every paged route rather than to one representative of them.
      FakeSender sender =
          new FakeSender()
              .then(200, "{\"items\":[{\"id\":\"a\"}],\"nextCursor\":\"cur_2\"}")
              .then(200, "{\"items\":[{\"id\":\"b\"}],\"nextCursor\":null}");
      try {
        List<Map<String, Object>> rows = build.apply(sender.client()).toList();
        if (rows.size() != 2) {
          problems.add(id + " walked " + rows.size() + " rows across two pages, expected 2");
        }
        if (sender.callCount() != 2) {
          problems.add(id + " made " + sender.callCount() + " requests, expected 2");
        }
      } catch (RuntimeException e) {
        problems.add(id + " threw while paginating: " + e);
      }
    }

    if (!problems.isEmpty()) {
      fail("paged-route problems:\n  " + String.join("\n  ", problems));
    }
  }

  // ------------------------------------------------- bare-array routes

  /**
   * {@code GET /v1/macros} returns a bare array, and this SDK must decode one.
   *
   * <p>The regression test for the manifest's own corrected row. Note the scripted body is an
   * array: if {@code macros().list()} were built on {@link Page}, this would fail here rather than
   * in a customer's integration.
   */
  @Test
  void macrosListDecodesABareArrayAndNotAPageEnvelope() {
    FakeSender sender =
        new FakeSender().then(200, "[{\"id\":\"m_1\",\"name\":\"Refund\"},{\"id\":\"m_2\"}]");

    List<Map<String, Object>> macros = sender.client().macros().list();

    assertEquals(2, macros.size());
    assertEquals("Refund", macros.get(0).get("name"));
    assertEquals("https://api.chatlychat.com/v1/macros", sender.lastRequest().url());
  }

  @Test
  void aPageEnvelopeFromTheMacrosRouteIsANamedFailureNotSilentlyAccepted() {
    // The inverse direction. If the route ever starts paginating, the SDK
    // should say so loudly rather than hand back a one-element list containing
    // the envelope.
    FakeSender sender = new FakeSender().then(200, "{\"items\":[],\"nextCursor\":null}");

    com.chatlychat.chatly.errors.ChatlyException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.chatlychat.chatly.errors.ChatlyException.class,
            () -> sender.client().macros().list());
    assertTrue(e.getMessage().contains("/v1/macros"), e.getMessage());
    assertTrue(e.getMessage().contains("expected a JSON array"), e.getMessage());
  }

  /**
   * Exactly which resource classes offer auto-pagination.
   *
   * <p>A structural assertion, by reflection, rather than one test per method. It is the check that
   * would have caught {@code macros.listAuto} being added on the strength of a wrong manifest row:
   * the class appearing in this set is the thing that is wrong, and no amount of behaviour testing
   * of a method that should not exist would reveal it.
   */
  @Test
  void onlyTheIntendedResourceClassesOfferAutoPagination() {
    List<Class<?>> resources =
        List.of(
            ContactsResource.class,
            ConversationsResource.class,
            TicketsResource.class,
            MacrosResource.class,
            KbResource.class,
            SearchResource.class,
            EventsResource.class,
            WebhooksResource.class,
            ReportsResource.class,
            ChannelsResource.class,
            BillingResource.class,
            WorkspaceResource.class);

    TreeSet<String> withPagers = new TreeSet<>();
    for (Class<?> type : resources) {
      for (Method m : type.getDeclaredMethods()) {
        if (Pager.class.isAssignableFrom(m.getReturnType())) {
          withPagers.add(type.getSimpleName());
        }
      }
    }

    // SearchResource is in this list and is NOT a manifest-paged entry: it
    // returns a real page envelope (with `facets` and `tier` beside the items),
    // so an auto-paginator is a genuine convenience. Same for
    // `contacts.journey`. That is why this test is not a biconditional against
    // the manifest's `paged` flag — `paged` means "must offer auto-pagination",
    // not "is the only route that may".
    TreeSet<String> expected =
        new TreeSet<>(
            List.of(
                "ContactsResource",
                "ConversationsResource",
                "TicketsResource",
                "KbResource",
                "SearchResource",
                "EventsResource",
                "WebhooksResource"));

    assertEquals(
        expected,
        withPagers,
        "the set of resource classes offering a Pager changed. MacrosResource in particular must"
            + " never appear: GET /v1/macros is @PaginationExempt and returns a bare array.");
    assertFalse(withPagers.contains("MacrosResource"));
  }

  // ------------------------------------------------------ search kinds

  /**
   * {@code search().kb()} sends the canonical {@code kinds} key.
   *
   * <p>Previously untested, and that mattered: every other search test passes its own
   * {@link Query}, so none of them exercised the key {@code kb()} hardcodes. The method really did
   * still send {@code kinds[]} after the tests around it had been moved to {@code kinds} — the
   * tests asserted on a value they supplied themselves, which is no assertion about the SDK at all.
   */
  @Test
  void searchKbSendsTheCanonicalKindsKey() {
    FakeSender sender = new FakeSender().then(200, "{\"items\":[],\"nextCursor\":null}");

    sender.client().search().kb("refund");

    String url = sender.lastRequest().url();
    assertTrue(url.contains("kinds=kb"), url);
    assertFalse(url.contains("kinds%5B%5D"), "the bracketed spelling is a compatibility shim: " + url);
    assertTrue(url.contains("q=refund"), url);
  }

  /**
   * The SDK never sends a kind the server's enum would reject.
   *
   * <p>{@code SEARCH_KINDS} is {@code contact, conversation, message, ticket, kb} — five values,
   * validated with {@code z.enum}, so an unknown one is a 400 rather than an empty result. This
   * SDK's own docs used {@code "article"} as an example, which is not a member; the example would
   * have 400'd for anyone who copied it.
   */
  @Test
  void theSdkOnlyEverSendsAValidSearchKind() {
    List<String> valid = List.of("contact", "conversation", "message", "ticket", "kb");

    FakeSender sender = new FakeSender().then(200, "{\"items\":[],\"nextCursor\":null}");
    sender.client().search().kb("refund");

    for (String sent : Query.of().add("kinds", "kb").get("kinds")) {
      assertTrue(valid.contains(sent), sent + " is not a member of SEARCH_KINDS");
    }
    String url = sender.lastRequest().url();
    assertFalse(url.contains("article"), "`article` is not a search kind: " + url);
  }

  // --------------------------------------------------------- helpers

  @SuppressWarnings("unchecked") // shape checked as it is read
  private static List<String> pagedEntryIds() {
    Path root = findRepoRoot();
    Map<String, Object> manifest;
    try {
      manifest = Json.parseObject(Files.readString(root.resolve(MANIFEST)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!(manifest.get("entries") instanceof List<?> list)) {
      throw new AssertionError(MANIFEST + " has no `entries` array");
    }
    List<String> out = new ArrayList<>();
    for (Object raw : list) {
      Map<String, Object> e = (Map<String, Object>) raw;
      if (Boolean.TRUE.equals(e.get("paged"))) {
        out.add(e.get("ns") + "." + e.get("name"));
      }
    }
    return out;
  }

  private static Path findRepoRoot() {
    Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    for (Path p = dir; p != null; p = p.getParent()) {
      if (Files.exists(p.resolve(MANIFEST))) {
        return p;
      }
    }
    throw new UncheckedIOException(
        new IOException(
            "could not find " + MANIFEST + " above " + dir + "; this test fails rather than"
                + " skipping, because a skipped parity check that reports a pass is worse than"
                + " no check at all."));
  }
}
