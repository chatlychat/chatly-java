package com.chatlychat.chatly;

import static com.chatlychat.chatly.SurfaceIds.BOT;
import static com.chatlychat.chatly.SurfaceIds.CONTACT;
import static com.chatlychat.chatly.SurfaceIds.CONVERSATION;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The 163 surface methods, each paired with a throwaway invocation — and the machinery that runs
 * one.
 *
 * <p>One list, used by two tests that ask different questions of it:
 *
 * <ul>
 *   <li>{@link SurfaceTest} — does each method issue the verb and URL the manifest declares?
 *   <li>{@link SurfaceLockTest} — executes every entry, records what came out, and writes
 *       {@code surface.lock.json} as evidence.
 * </ul>
 *
 * <p>Both run the client. Neither reads source text. That matters: the first version of
 * {@code scripts/check-sdk-surface-parity.mjs} matched path strings in SDK <em>source</em>, and so
 * counted a doc comment that merely mentioned a route as an implementation of it — this SDK was
 * credited 55/55 while two of those methods did not exist, because a Javadoc paragraph explaining
 * that two routes were broken contained their paths. Text matching cannot tell documentation from
 * code, and it cannot see a path assembled through a helper either. Executing the method can do
 * both.
 *
 * <p>The entries themselves live in four {@code SurfaceEntries*} classes: 163 of them plus this
 * harness is far past CONTRACT &sect;9's 650-line ceiling. This file is the harness, those are the
 * data, and the sample ids they share are in {@link SurfaceIds}.
 *
 * <p>The {@code ns} and {@code name} on each entry are the manifest's, so the lock this produces is
 * directly comparable to {@code packages/sdk-spec/surface.json}.
 */
final class SurfaceCalls {

  /**
   * One surface method.
   *
   * @param ns the manifest's namespace, e.g. {@code contacts}
   * @param name the manifest's method name, e.g. {@code add_tags}
   * @param http the verb the manifest declares
   * @param pathTemplate the manifest's path, placeholders and all: {@code /v1/contacts/{id}/tags}
   * @param concretePath the path this invocation actually produces, with the arguments below
   *     substituted — the thing {@link SurfaceTest} compares against
   * @param invoke calls the method with throwaway arguments and returns whatever it handed back, so
   *     the caller can check the shape of the <em>result</em> and not only of the request
   */
  record Entry(
      String ns,
      String name,
      String http,
      String pathTemplate,
      String concretePath,
      Function<Chatly, Object> invoke) {

    /** {@code ns.name}, for an error message. */
    String id() {
      return ns + "." + name;
    }

    /**
     * The response shape this route answers with, from {@link ResponseShape#declared()}.
     *
     * <p>Not a field on this record, and not a body string either. It used to be a body string per
     * row, which meant the body and the route's real shape were two facts kept in step by hand; now
     * there is one hand-written column, {@link ResponseShape} owns it, and the fixture is derived
     * from <em>that</em> rather than from {@code surface.json}. A row with no declaration fails
     * rather than defaulting.
     */
    ResponseShape shape() {
      return ResponseShape.of(id());
    }
  }

  private static List<Entry> entries;

  /** Every entry, assembled once from the four data classes. */
  static synchronized List<Entry> all() {
    if (entries == null) {
      List<Entry> e = new ArrayList<>();
      SurfaceEntriesCore.add(e);
      SurfaceEntriesAutomation.add(e);
      SurfaceEntriesConfig.add(e);
      SurfaceEntriesInbox.add(e);
      entries = List.copyOf(e);
    }
    return entries;
  }

  /**
   * Routes whose {@code array} body holds strings rather than objects, and the body to feed them.
   *
   * <p>There is exactly one: {@code channels.adapters}, whose handler is
   * {@code z.array(z.string())} and which sends {@code ["web","email",…]}. Its top-level shape
   * really is {@code array}, so {@link ResponseShape} declares it {@code ARRAY} like the other 31 —
   * what differs is the ELEMENT type, and the shared {@code [{"id":"x_1"}]} fixture could not
   * exercise a decoder whose job is to refuse exactly that body.
   *
   * <p>So the shape column stays four values wide and this one route gets its own body. Kept as a
   * map with an assertion over its size in {@link SurfaceLockTest} rather than as a field on
   * {@link Entry}, so it cannot quietly grow into a per-route body table — which is what this suite
   * used to have, and what made the body and the route's real shape two facts kept in step by hand.
   */
  static final Map<String, String> SCALAR_ARRAY_BODIES =
      Map.of("channels.adapters", "[\"web\",\"email\",\"api\"]");

  /**
   * One request, as the transport saw it, with the base URL and query string stripped — plus the
   * value the method handed its caller.
   *
   * @param value whatever the method returned, {@code null} for the one {@code void} method. The
   *     request being right says nothing about the decode, and the decode is where
   *     {@code macros.list} broke two other SDKs.
   */
  record Issued(String method, String path, Object value) {}

  /**
   * Execute one entry against a scripted transport and report the single request it made.
   *
   * <p>The scripted response is the body of the route's <strong>own declared shape</strong>, not
   * one body for all 163. A fake that answers every route with {@code {items, nextCursor}} agrees
   * with any client that expects a page everywhere, which is precisely how the .NET and Rust
   * clients shipped a paginator over the bare-array {@code /v1/macros} with green suites.
   *
   * <p>One route is fed a body of its own: see {@link #SCALAR_ARRAY_BODIES}.
   *
   * @throws AssertionError if the method made zero or more than one request — a surface method is
   *     one route, and a method that quietly makes two is not something a lock can describe
   */
  static Issued execute(Entry entry) {
    ResponseShape shape = entry.shape();
    FakeSender sender = new FakeSender();
    sender.then(shape.status(), SCALAR_ARRAY_BODIES.getOrDefault(entry.id(), shape.body()));
    Object value = entry.invoke().apply(sender.clientNoRetries());

    if (sender.callCount() != 1) {
      throw new AssertionError(
          entry.id() + " made " + sender.callCount() + " requests; a surface method must make 1");
    }
    Transport.Request request = sender.lastRequest();
    String url = request.url();
    if (!url.startsWith(Chatly.DEFAULT_BASE_URL)) {
      throw new AssertionError(entry.id() + " did not call the default base URL: " + url);
    }
    String path = url.substring(Chatly.DEFAULT_BASE_URL.length());
    int queryStart = path.indexOf('?');
    if (queryStart >= 0) {
      path = path.substring(0, queryStart);
    }
    return new Issued(request.method(), path, value);
  }

  /**
   * The auto-paginating form of every route that answers with a page envelope.
   *
   * <p>One map, two tests asking opposite questions of it: {@link ListShapeTest} checks that each
   * walker walks two pages and stops, and {@link SurfaceLockTest} checks that each one
   * <em>refuses</em> a bare array. Both also assert the key set, from different authorities — the
   * manifest's {@code paged} flag there, this SDK's own {@link ResponseShape} declaration here — so
   * a route that fell out of one would still be caught by the other.
   *
   * <p>{@code macros.list} is deliberately absent and must stay absent: {@code GET /v1/macros} is
   * {@code @PaginationExempt} and has no cursor, so a walker over it could only ever return one
   * page while looking complete.
   */
  static Map<String, Function<Chatly, Pager<Map<String, Object>>>> pagers() {
    Map<String, Function<Chatly, Pager<Map<String, Object>>>> m = new LinkedHashMap<>();
    m.put("contacts.list", c -> c.contacts().listAuto());
    m.put("contacts.journey", c -> c.contacts().journeyAuto(CONTACT, Query.of()));
    m.put("conversations.list", c -> c.conversations().listAuto());
    m.put("conversations.messages", c -> c.conversations().messagesAuto(CONVERSATION, Query.of()));
    m.put("conversations.unattended", c -> c.conversations().unattendedAuto());
    m.put("tickets.list", c -> c.tickets().listAuto());
    m.put("kb.articles", c -> c.kb().articlesAuto());
    m.put("search.query", c -> c.search().queryAuto("refund", Query.of()));
    m.put("events.list", c -> c.events().listAuto());
    m.put("webhooks.deliveries", c -> c.webhooks().deliveriesAuto());
    // The three that arrived with the completed surface. All three were absent
    // from the manifest's `paged` flag when it first listed them, and all three
    // really do answer `{items, nextCursor, hasMore}` — each one ends in
    // `buildPaginatedResponse` inside its service, which is why the handler and
    // not the flag is the thing to read.
    m.put("bots.list", c -> c.bots().listAuto());
    m.put("bots.deliveries", c -> c.bots().deliveriesAuto(BOT));
    m.put("triggers.list", c -> c.triggers().listAuto());
    return m;
  }

  /**
   * Rewrite a concrete path back to the manifest's template form.
   *
   * <p>{@code /v1/contacts/c_1/tags/vip} against the template
   * {@code /v1/contacts/{id}/tags/{tag}} yields the template's own text. Matching is positional,
   * segment by segment: wherever the template has a placeholder, the placeholder is taken, and
   * wherever it has a literal, the actual segment must equal it. A literal that does not match is an
   * error rather than a silent substitution, because that is precisely the typo this is meant to
   * catch — {@code /v1/tickets/queue} against {@code /v1/tickets/queues} must not pass.
   */
  static String normalise(String actualPath, String template, String entryId) {
    String[] actual = actualPath.split("/", -1);
    String[] parts = template.split("/", -1);
    if (actual.length != parts.length) {
      throw new AssertionError(
          entryId
              + ": issued "
              + actualPath
              + " which has "
              + actual.length
              + " segments, but the manifest template "
              + template
              + " has "
              + parts.length);
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        out.append('/');
      }
      String part = parts[i];
      if (part.startsWith("{") && part.endsWith("}")) {
        if (actual[i].isEmpty()) {
          throw new AssertionError(
              entryId + ": issued an empty segment where " + part + " was expected: " + actualPath);
        }
        out.append(part);
      } else {
        if (!part.equals(actual[i])) {
          throw new AssertionError(
              entryId
                  + ": issued "
                  + actualPath
                  + " but the manifest declares "
                  + template
                  + " (segment "
                  + i
                  + ": got \""
                  + actual[i]
                  + "\", expected \""
                  + part
                  + "\")");
        }
        out.append(part);
      }
    }
    return out.toString();
  }

  private SurfaceCalls() {}
}
