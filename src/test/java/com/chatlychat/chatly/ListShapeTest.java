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

  /**
   * Two spellings, for the same reason as {@code SurfaceLockTest}: the monorepo's
   * {@code packages/sdk-spec/surface.json}, and the copy a public mirror vendors at its root,
   * where there is no {@code packages/} above the package. This test had only the first, so it
   * passed here and failed in the mirror's CI — which is the one CI this project has that runs.
   */
  private static final String[] MANIFEST_CANDIDATES = {
    "packages/sdk-spec/surface.json", "sdk-spec/surface.json",
  };

  private static final String MANIFEST = "packages/sdk-spec/surface.json";

  /**
   * The manifest's {@code paged} count, as an anti-vacuity floor.
   *
   * <p><strong>13.</strong> It read 8, then 7, then 10, then 13, and the journey is the point: the
   * flag has been wrong in <em>both</em> directions. {@code macros.list} was flagged and is
   * {@code @PaginationExempt} — a bare array, 200 rows by usage rank — while
   * {@code contacts.journey}, {@code conversations.unattended} and {@code search.query} return real
   * cursors and were not flagged. Removing the false one took it to 7; adding the three missing
   * ones took it to 10.
   *
   * <p>10 to 13 is the completed surface: {@code bots.list}, {@code bots.deliveries} and
   * {@code triggers.list}, each of which ends in {@code buildPaginatedResponse} inside its service
   * and so was easy to miss by reading only the controller. {@code billing.invoices} is
   * deliberately NOT a fourteenth — it is cursor-paginated and spells its envelope
   * {@code {data, nextCursor}}, so a walker keyed on {@code items} would report an empty page
   * forever.
   *
   * <p>The floor matters because every assertion below iterates the paged entries: if the manifest
   * stopped parsing, or someone quietly unflagged a route, the loop would run over a shorter list
   * and this file would report a pass for pagination it never checked. That failure mode — a gate
   * reporting the number of things it managed to parse rather than the number that exist — has
   * happened twice in this repository, so the count is pinned rather than trusted.
   */
  private static final int EXPECTED_PAGED_ENTRIES = 13;

  // ------------------------------------------------------- paged routes

  /**
   * The auto-paginating form of each route the manifest marks {@code paged}.
   *
   * <p>{@link SurfaceCalls#pagers()}, not a second copy of it. The same map is walked here for the
   * positive case (two pages, then stop) and in {@link SurfaceLockTest} for the negative one (a
   * bare array must throw); two copies would let one of those tests quietly stop covering a route
   * the other still covered.
   */
  private static Map<String, Function<Chatly, Pager<Map<String, Object>>>> pagers() {
    return SurfaceCalls.pagers();
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

  // -------------------------------------------- the two odd envelopes

  /**
   * {@code GET /v1/channels/adapters} is an array of STRINGS, and the SDK decodes it as one.
   *
   * <p>The manifest flags it as the single row where a decoder built for {@code [{…}]} fails. The
   * generic {@code array} helper would not fail — it wraps a scalar row as {@code {"value": "web"}}
   * — so the failure would have been a list of maps a caller has to unwrap, discovered by printing
   * it. Hence a separate decoder, and hence this test: the positive case, plus the loud refusal
   * when an element is not a string, which is what tells a future reader that the wrapping
   * behaviour is deliberately not in play here.
   */
  @Test
  void channelAdaptersDecodesAnArrayOfStringsAndRefusesAnythingElse() {
    FakeSender sender = new FakeSender().then(200, "[\"web\",\"email\",\"whatsapp\"]");

    List<String> adapters = sender.client().channels().adapters();

    assertEquals(List.of("web", "email", "whatsapp"), adapters);
    assertEquals("https://api.chatlychat.com/v1/channels/adapters", sender.lastRequest().url());

    com.chatlychat.chatly.errors.ChatlyException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.chatlychat.chatly.errors.ChatlyException.class,
            () -> new FakeSender().then(200, "[{\"name\":\"web\"}]").client().channels().adapters());
    assertTrue(e.getMessage().contains("/v1/channels/adapters"), e.getMessage());
    assertTrue(e.getMessage().contains("to be a string"), e.getMessage());
  }

  /**
   * {@code GET /v1/billing/invoices} pages on {@code data}, and nothing in this SDK walks it.
   *
   * <p>It is the one cursor-paginated route declared {@code object}. Two halves, and the second is
   * the one that matters: {@link BillingResource#invoices()} hands the envelope back whole, and
   * {@link Page#fromDecoded} <em>refuses</em> that envelope — so a walker pointed at this route
   * fails loudly instead of reading {@code items}, finding nothing, and reporting a workspace with
   * no invoices. That silent-empty-page outcome is exactly what the Rust client shipped on a
   * different route.
   */
  @Test
  void billingInvoicesKeepsItsDataEnvelopeAndIsNotWalkable() {
    String envelope = "{\"data\":[{\"id\":\"in_1\"}],\"nextCursor\":\"cur_2\"}";

    Map<String, Object> page = new FakeSender().then(200, envelope).client().billing().invoices();
    assertTrue(page.get("data") instanceof List, "the data array survives: " + page);
    assertEquals("cur_2", page.get("nextCursor"));
    assertFalse(page.containsKey("items"), "nothing renames `data` to `items`");

    org.junit.jupiter.api.Assertions.assertThrows(
        com.chatlychat.chatly.errors.ChatlyException.class,
        () -> Page.fromDecoded(Json.parse(envelope), "/v1/billing/invoices"),
        "a {data, nextCursor} envelope must not decode as a page");
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
   * Offers a pager <strong>if and only if</strong> the manifest marks the route paged.
   *
   * <p>A biconditional, derived from the manifest rather than from a hand-kept list. This was one
   * direction only until the {@code paged} flag was fixed: three routes that return real cursors
   * ({@code contacts.journey}, {@code conversations.unattended}, {@code search.query}) were not
   * flagged, so insisting on "⟺" would have condemned three correct pagers. With the flag right in
   * both directions the stronger assertion holds, and it is the one that catches both failures —
   * a pager missing from a paged route, and a pager offered for a route that returns a bare array.
   *
   * <p>Reflection by resource class rather than by method, because a method name cannot be derived
   * from a manifest row: the auto-paginating forms are {@code listAuto}, {@code journeyAuto},
   * {@code unattendedAuto}, {@code messagesAuto}, {@code articlesAuto}, {@code queryAuto} and
   * {@code deliveriesAuto}. What is checkable is which classes have any, and that is exactly what
   * would have gone wrong had {@code macros.listAuto} been written on the strength of the wrong
   * manifest row.
   *
   * <p>It is class-level, so it is coarse in one direction: {@code BillingResource} owns
   * {@code billing.invoices}, which is cursor-paginated under a {@code data} key and must NOT have
   * a walker, and this assertion would not notice one — the manifest marks that route
   * {@code paged: false}, so the class is expected to have no pager at all, which is the thing it
   * does check. A walker added there would fail here as an unexpected class.
   */
  @Test
  void aResourceOffersAPagerExactlyWhenTheManifestSaysPaged() {
    Map<String, Class<?>> byNamespace = new LinkedHashMap<>();
    byNamespace.put("contacts", ContactsResource.class);
    byNamespace.put("conversations", ConversationsResource.class);
    byNamespace.put("tickets", TicketsResource.class);
    byNamespace.put("macros", MacrosResource.class);
    byNamespace.put("kb", KbResource.class);
    byNamespace.put("search", SearchResource.class);
    byNamespace.put("events", EventsResource.class);
    byNamespace.put("webhooks", WebhooksResource.class);
    byNamespace.put("reports", ReportsResource.class);
    byNamespace.put("channels", ChannelsResource.class);
    byNamespace.put("billing", BillingResource.class);
    byNamespace.put("workspace", WorkspaceResource.class);
    byNamespace.put("ai", AiResource.class);
    byNamespace.put("bots", BotsResource.class);
    byNamespace.put("bot_routes", BotRoutesResource.class);
    byNamespace.put("brands", BrandsResource.class);
    byNamespace.put("canvas_steps", CanvasStepsResource.class);
    byNamespace.put("customer_cards", CustomerCardsResource.class);
    byNamespace.put("customer_card_endpoints", CustomerCardEndpointsResource.class);
    byNamespace.put("docs_sources", DocsSourcesResource.class);
    byNamespace.put("email_templates", EmailTemplatesResource.class);
    byNamespace.put("knowledge_gaps", KnowledgeGapsResource.class);
    byNamespace.put("triggers", TriggersResource.class);
    // Not paged — `visitors.start_conversation` is the whole namespace and it
    // answers an object. The row is here so the `doHavePagers` side of this
    // assertion can see the class at all: a walker added to it would otherwise
    // be invisible rather than an unexpected class.
    byNamespace.put("visitors", VisitorsResource.class);

    // What the manifest implies: a class owning at least one paged entry.
    TreeSet<String> shouldHavePagers = new TreeSet<>();
    for (String id : pagedEntryIds()) {
      String ns = id.substring(0, id.indexOf('.'));
      Class<?> type = byNamespace.get(ns);
      // `root` has no resource class — whoami lives on Chatly itself — and it
      // is not paged, so a null here means the manifest grew a namespace this
      // map has not been told about. Fail rather than skip it.
      assertTrue(type != null, "no resource class mapped for namespace `" + ns + "` (from " + id + ")");
      shouldHavePagers.add(type.getSimpleName());
    }

    // What the code actually offers.
    TreeSet<String> doHavePagers = new TreeSet<>();
    for (Class<?> type : byNamespace.values()) {
      for (Method m : type.getDeclaredMethods()) {
        if (Pager.class.isAssignableFrom(m.getReturnType())) {
          doHavePagers.add(type.getSimpleName());
        }
      }
    }

    assertEquals(
        shouldHavePagers,
        doHavePagers,
        "a resource must offer an auto-paginator exactly when the manifest marks one of its routes"
            + " paged. MacrosResource in particular must never appear: GET /v1/macros is"
            + " @PaginationExempt and returns a bare array.");
    assertFalse(doHavePagers.contains("MacrosResource"));
  }

  /**
   * The two forms of {@code /v1/search}, and what each one costs you.
   *
   * <p>The manifest notes that the single-page form returns the whole envelope — {@code facets} and
   * {@code tier} sit beside the hits — and that "a paginator that yields only hits throws those
   * away". Both halves of that are asserted here, because the tradeoff is a real choice a caller
   * has to make and an undocumented one is a surprise: if you need the facets, do not reach for the
   * auto-paginator.
   *
   * <p>{@code tier} is the more useful of the two in practice. It names which backend answered
   * (Postgres full-text versus the vector index), and it is the first thing to look at when results
   * are not what you expected.
   */
  @Test
  void theSinglePageSearchFormKeepsTheWholeEnvelopeAndThePagerDoesNot() {
    String envelope =
        "{\"items\":[{\"id\":\"h1\"}],\"nextCursor\":null,"
            + "\"facets\":{\"kind\":{\"kb\":2}},\"tier\":\"fts\"}";

    // Single page: the envelope survives, reachable through raw().
    FancyPage single = FancyPage.of(new FakeSender().then(200, envelope));
    assertEquals("fts", single.page.raw().get("tier"));
    assertTrue(single.page.raw().containsKey("facets"));
    assertEquals(1, single.page.items().size());

    // Auto-paginated: rows only. facets and tier are not recoverable from it,
    // which is the documented cost of walking.
    List<Map<String, Object>> walked =
        new FakeSender().then(200, envelope).client().search().queryAuto("refund", Query.of())
            .toList();
    assertEquals(1, walked.size());
    assertEquals("h1", walked.get(0).get("id"));
    assertFalse(walked.get(0).containsKey("tier"), "a row is a hit, not the envelope");
  }

  /** Tiny holder so the assertion above reads as two parallel cases. */
  private record FancyPage(Page<Map<String, Object>> page) {
    static FancyPage of(FakeSender sender) {
      return new FancyPage(sender.client().search().query("refund"));
    }
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
      manifest = Json.parseObject(Files.readString(manifestIn(root)));
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
      if (anyCandidateExists(p)) {
        return p;
      }
    }
    throw new UncheckedIOException(
        new IOException(
            "could not find " + MANIFEST + " above " + dir + "; this test fails rather than"
                + " skipping, because a skipped parity check that reports a pass is worse than"
                + " no check at all."));
  }

  /** Whichever candidate exists under {@code root}. */
  private static java.nio.file.Path manifestIn(java.nio.file.Path root) {
    for (String candidate : MANIFEST_CANDIDATES) {
      java.nio.file.Path p = root.resolve(candidate);
      if (Files.exists(p)) {
        return p;
      }
    }
    return root.resolve(MANIFEST);
  }

  /** True when any candidate exists under {@code dir}. */
  private static boolean anyCandidateExists(java.nio.file.Path dir) {
    for (String candidate : MANIFEST_CANDIDATES) {
      if (Files.exists(dir.resolve(candidate))) {
        return true;
      }
    }
    return false;
  }
}
