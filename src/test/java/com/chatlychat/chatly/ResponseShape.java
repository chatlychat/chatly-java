package com.chatlychat.chatly;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The top-level shape of a successful response, and what this SDK's decoder expects from each of
 * the 55 routes.
 *
 * <h2>Read this before changing a value</h2>
 *
 * <p>{@link #DECLARED} is <strong>hand-written, from the handlers</strong>. It is deliberately
 * <em>not</em> read from {@code packages/sdk-spec/surface.json}, even though the surface-lock suite
 * loads that manifest for the path templates and compares this column against it.
 *
 * <p>The reason is not theoretical. A fixture derived from the manifest agrees with whatever the
 * manifest says, <em>including when the manifest is wrong</em>, and the manifest has been wrong:
 * {@code macros.list} carried {@code "paged": true} for a day while its handler is
 * {@code @PaginationExempt} and answers a bare 200-row array. The .NET and Rust clients built
 * paginators over it and raised a decode error against the real API with green suites, because
 * their fake transports answered <em>every</em> route with {@code {items, nextCursor}}. A fake that
 * always returns the shape the SDK expects cannot discover that the SDK expects the wrong shape;
 * and a fake driven by the manifest's own {@code shape} field cannot discover that the manifest
 * does. The Rust client's failure was the worse kind — its derived page deserializer read the bare
 * array as an <em>empty page</em> and handed it back with a 200, a wrong answer rather than a
 * missing error.
 *
 * <p>So this table is a second opinion. {@link SurfaceLockTest} feeds each method the shape
 * declared here, asserts the value handed back really has that shape, and writes the declaration
 * into {@code surface.lock.json}; {@code scripts/check-sdk-surface-parity.mjs} then diffs eight
 * SDKs' independently written columns against the manifest. A wrong manifest row would need eight
 * authors to misread the same handler.
 *
 * <h2>Four rows that look wrong and are not</h2>
 *
 * <ul>
 *   <li>{@code contacts.remove_tag} is a {@code DELETE} that answers 200 with the <em>surviving
 *       contact</em> — the same object the tag {@code POST} returns.
 *   <li>{@code conversations.cancel_scheduled} is a {@code DELETE} that answers 200 with the
 *       <em>cancelled row</em> ({@code @ZodResponse(scheduledMessageSchema)}). Two of the three
 *       deletes on this surface have bodies, so the verb says nothing about the shape.
 *   <li>{@code macros.delete} is the <strong>only</strong> real 204 here: {@code @HttpCode(204)},
 *       handler returns nothing.
 *   <li>{@code reports.agents} and {@code reports.conversations_by_day} are bare arrays — per-agent
 *       rows and per-day buckets, no envelope — while the other two report routes are objects.
 * </ul>
 */
enum ResponseShape {

  /** A bare JSON array. 14 routes. */
  ARRAY("array", 200, "[{\"id\":\"x_1\"}]"),

  /**
   * The cursor envelope: {@code items} plus {@code nextCursor}, with whatever sits beside them
   * ({@code hasMore} on most, {@code total} on {@code conversations.unattended}, {@code tier} and
   * {@code facets} on {@code search.query}). 10 routes.
   */
  PAGE("page", 200, "{\"items\":[{\"id\":\"x_1\"}],\"nextCursor\":null}"),

  /** A JSON object that is not a page. 30 routes. */
  OBJECT("object", 200, "{\"id\":\"x_1\"}"),

  /**
   * No body at all — a real 204. Exactly 1 route.
   *
   * <p>The body is {@code null}, not {@code ""} and not {@code "{}"}: a decoder that insists on
   * parsing a body must fail here, which is the whole point of this value existing separately from
   * {@link #OBJECT}.
   */
  EMPTY("empty", 204, null);

  private final String wire;
  private final int status;
  private final String body;

  ResponseShape(String wire, int status, String body) {
    this.wire = wire;
    this.status = status;
    this.body = body;
  }

  /** The spelling used in {@code surface.json} and in every SDK's lock. */
  String wire() {
    return wire;
  }

  /** The HTTP status the API answers this shape with. */
  int status() {
    return status;
  }

  /** A success body of this shape, as the API would send it. {@code null} for {@link #EMPTY}. */
  String body() {
    return body;
  }

  // ------------------------------------------------------------- the declaration

  private static final Map<String, ResponseShape> DECLARED = declare();

  private static Map<String, ResponseShape> declare() {
    Map<String, ResponseShape> m = new LinkedHashMap<>();

    m.put("root.whoami", OBJECT);

    m.put("contacts.list", PAGE);
    m.put("contacts.get", OBJECT);
    m.put("contacts.upsert", OBJECT);
    m.put("contacts.bulk_upsert", OBJECT);
    m.put("contacts.merge", OBJECT);
    m.put("contacts.add_tags", OBJECT);
    // A DELETE with a body: the surviving contact.
    m.put("contacts.remove_tag", OBJECT);
    m.put("contacts.unsubscribe", OBJECT);
    m.put("contacts.journey", PAGE);

    m.put("conversations.list", PAGE);
    m.put("conversations.get", OBJECT);
    m.put("conversations.messages", PAGE);
    m.put("conversations.send", OBJECT);
    m.put("conversations.unattended", PAGE);
    m.put("conversations.agent_handling", OBJECT);
    m.put("conversations.set_agent_handling", OBJECT);
    m.put("conversations.generated_replies", ARRAY);
    m.put("conversations.generate_reply", OBJECT);
    m.put("conversations.scheduled_messages", ARRAY);
    m.put("conversations.schedule_message", OBJECT);
    // Also a DELETE with a body: the row it just cancelled.
    m.put("conversations.cancel_scheduled", OBJECT);

    m.put("tickets.list", PAGE);
    m.put("tickets.comments", ARRAY);
    m.put("tickets.for_conversation", ARRAY);
    m.put("tickets.queues", ARRAY);
    m.put("tickets.merge", OBJECT);
    m.put("tickets.split", OBJECT);
    m.put("tickets.bulk", OBJECT);

    // @PaginationExempt, bare array, capped at 200 by usage rank. The row that
    // cost two other SDKs a decode error in production.
    m.put("macros.list", ARRAY);
    m.put("macros.create", OBJECT);
    m.put("macros.update", OBJECT);
    // The only 204 on the surface.
    m.put("macros.delete", EMPTY);

    m.put("kb.articles", PAGE);
    m.put("kb.article", OBJECT);
    m.put("kb.collections", ARRAY);

    m.put("search.query", PAGE);

    m.put("events.track", OBJECT);
    m.put("events.list", PAGE);

    m.put("webhooks.deliveries", PAGE);
    m.put("webhooks.delivery", OBJECT);
    m.put("webhooks.redeliver", OBJECT);

    m.put("reports.overview", OBJECT);
    // Arrays, both of them — per-agent rows and per-day buckets, no envelope.
    m.put("reports.agents", ARRAY);
    m.put("reports.conversations_by_day", ARRAY);
    m.put("reports.sla", OBJECT);

    m.put("channels.list", ARRAY);
    m.put("channels.get", OBJECT);

    m.put("billing.plans", ARRAY);
    m.put("billing.usage", OBJECT);

    m.put("workspace.settings", OBJECT);
    m.put("workspace.labels", ARRAY);
    m.put("workspace.inboxes", ARRAY);
    m.put("workspace.team_members", ARRAY);
    m.put("workspace.agent_skills", OBJECT);

    return Map.copyOf(m);
  }

  /** The whole declaration, keyed by {@code ns.name}. */
  static Map<String, ResponseShape> declared() {
    return DECLARED;
  }

  /**
   * The shape declared for one route.
   *
   * @throws AssertionError if the route has no declaration — a new surface method must be given one
   *     by hand, not defaulted, because a default is how a column becomes 55 {@code object}s
   */
  static ResponseShape of(String entryId) {
    ResponseShape shape = DECLARED.get(entryId);
    if (shape == null) {
      throw new AssertionError(
          entryId
              + " has no declared response shape in ResponseShape.DECLARED. Read the handler's"
              + " @ZodResponse schema and add the row; do not copy it from surface.json, which is"
              + " the document this column exists to second-guess.");
    }
    return shape;
  }

  // --------------------------------------------------------- what came back

  /**
   * The shape a decoded value actually has, named in the same vocabulary.
   *
   * <p>Returns a {@code String} rather than a {@link ResponseShape} so that a value which is none
   * of the four — a bare {@code Long} from a route that should answer an object, say — is reported
   * as what it is instead of being forced into the enum or crashing the assertion.
   *
   * <p>Both of this SDK's page representations count as {@code page}: the typed {@link Page}, and a
   * raw {@code Map} that still has an {@code items} list beside a {@code nextCursor} key. The
   * second case is the one that bites — a method on a {@code page} route that handed back
   * {@code transport.get(...)} undigested, or one on an {@code object} route that happened to be
   * pointed at a paginated path, both land there.
   */
  static String actual(Object value) {
    if (value == null) {
      return EMPTY.wire();
    }
    if (value instanceof Page<?>) {
      return PAGE.wire();
    }
    if (value instanceof List<?>) {
      return ARRAY.wire();
    }
    if (value instanceof Pager<?>) {
      // A lazy walker is not a response shape at all: nothing has been fetched
      // yet, so calling it `page` would credit the method with a decode it has
      // not performed. Named distinctly so the failure says so.
      return "pager(unfetched)";
    }
    if (value instanceof Map<?, ?> map) {
      return map.get("items") instanceof List<?> && map.containsKey("nextCursor")
          ? PAGE.wire()
          : OBJECT.wire();
    }
    return "scalar(" + value.getClass().getSimpleName() + ")";
  }
}
