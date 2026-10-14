package com.chatlychat.chatly;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The top-level shape of a successful response, and what this SDK's decoder expects from each of
 * the 162 routes.
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
 * <h2>Rows that look wrong and are not</h2>
 *
 * <ul>
 *   <li>{@code contacts.remove_tag} is a {@code DELETE} that answers 200 with the <em>surviving
 *       contact</em> — the same object the tag {@code POST} returns.
 *   <li>{@code conversations.cancel_scheduled} is a {@code DELETE} that answers 200 with the
 *       <em>cancelled row</em> ({@code @ZodResponse(scheduledMessageSchema)}). Two of the three
 *       deletes on this surface have bodies, so the verb says nothing about the shape.
 *   <li>{@code macros.delete} is the <strong>only</strong> real 204 here: {@code @HttpCode(204)},
 *       handler returns nothing.
 *   <li>{@code reports.agents}, {@code reports.conversations_by_day},
 *       {@code reports.attribution} and {@code reports.sources} are bare arrays — per-agent rows,
 *       per-day buckets, per-dimension rows — while the other nine report routes are objects.
 *   <li>{@code billing.invoices} is genuinely cursor-paginated and is {@code object} anyway: its
 *       envelope is {@code {data, nextCursor}}, so a {@code page} declaration would hand it to a
 *       decoder reading {@code items} and get an empty page with a 200.
 *   <li>{@code conversations.linked}, {@code tickets.time} and {@code macros.templates} each carry
 *       an {@code items} array with no cursor beside it. Objects, not pages.
 *   <li>{@code email_templates.delete}, {@code brands.delete}, {@code ai.delete_tool},
 *       {@code bot_routes.delete}, {@code triggers.delete}, {@code customer_cards.delete},
 *       {@code customer_card_endpoints.delete} and {@code docs_sources.delete} are all DELETEs with
 *       bodies. {@code macros.delete} remains the only real 204 on the surface.
 * </ul>
 */
enum ResponseShape {

  /** A bare JSON array. 33 routes. */
  ARRAY("array", 200, "[{\"id\":\"x_1\"}]"),

  /**
   * The cursor envelope: {@code items} plus {@code nextCursor}, with whatever sits beside them
   * ({@code hasMore} on the seven built by {@code buildPaginatedResponse}, {@code total} on
   * {@code conversations.unattended}, {@code tier} and {@code facets} on {@code search.query}, and
   * nothing at all beside the two on the rest). 13 routes.
   */
  PAGE("page", 200, "{\"items\":[{\"id\":\"x_1\"}],\"nextCursor\":null}"),

  /** A JSON object that is not a page. 115 routes. */
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
    // GET /v1/me — @ZodResponse(meSchema), a z.discriminatedUnion('principal')
    // over two OBJECT schemas. A union of objects is an object; what varies is
    // which keys, not the top-level shape.
    m.put("root.me", OBJECT);

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

    // ----------------------------------------------------------------------
    // The 104 routes that completed the surface on 2026-10-14.
    //
    // Derived the same way as the 55 above: by reading each handler's
    // `@ZodResponse` schema in `apps/api/src`, resolving the named schema to
    // `z.array(…)` or `z.object(…)`, and treating `@HttpCode(204)` as `empty`.
    // Six needed the schema file rather than the decorator — the three `page`
    // envelopes below are `z.object({items, nextCursor, hasMore})`,
    // `bots.create` is a union of two object schemas, `tickets.lineage` is an
    // IIFE returning one, and `workspace.key_scopes` wraps its `z.array` in a
    // comment that hides it from a naive read of the decorator.
    //
    // NOT copied from surface.json. Read this enum's class javadoc for why
    // that distinction is the whole value of this table.
    // ----------------------------------------------------------------------

    m.put("ai.add_provider", OBJECT);
    m.put("ai.create_tool", OBJECT);
    m.put("ai.delete_tool", OBJECT);
    m.put("ai.generate_tone", OBJECT);
    m.put("ai.providers", OBJECT);
    m.put("ai.quota", OBJECT);
    // A PUT that answers the stored rule set as a bare array — same as the GET.
    m.put("ai.set_tone", ARRAY);
    m.put("ai.test_provider", OBJECT);
    m.put("ai.tone", ARRAY);
    m.put("ai.tone_preview", OBJECT);
    m.put("ai.tools", ARRAY);
    m.put("ai.update_tool", OBJECT);

    // Cursor-paginated and still an OBJECT: its envelope is {data, nextCursor},
    // not {items, …}, so Page cannot decode it and no walker is offered.
    m.put("billing.invoices", OBJECT);
    m.put("billing.summary", OBJECT);
    m.put("billing.usage_daily", OBJECT);
    m.put("billing.usage_monthly", OBJECT);

    m.put("bot_routes.create", OBJECT);
    m.put("bot_routes.delete", OBJECT);
    m.put("bot_routes.fields", OBJECT);
    m.put("bot_routes.list", ARRAY);
    m.put("bot_routes.resolve", OBJECT);
    m.put("bot_routes.update", OBJECT);

    m.put("bots.analytics", OBJECT);
    m.put("bots.create", OBJECT);
    m.put("bots.deliveries", PAGE);
    m.put("bots.delivery", OBJECT);
    m.put("bots.get", OBJECT);
    // {items, nextCursor, hasMore} from buildPaginatedResponse, in the service.
    m.put("bots.list", PAGE);
    m.put("bots.redeliver", OBJECT);
    m.put("bots.rotate_external_secret", OBJECT);
    m.put("bots.test_delivery", OBJECT);
    m.put("bots.update", OBJECT);
    m.put("bots.update_external", OBJECT);

    m.put("brands.create", OBJECT);
    m.put("brands.delete", OBJECT);
    m.put("brands.list", ARRAY);
    m.put("brands.update", OBJECT);

    m.put("canvas_steps.get", OBJECT);
    m.put("canvas_steps.resolve", OBJECT);

    // An array of STRINGS, not objects (z.array(z.string())). The top-level
    // shape is still `array`; SurfaceCalls.SCALAR_ARRAY_BODIES feeds it a body
    // whose elements match, because the shared array fixture could not.
    m.put("channels.adapters", ARRAY);
    m.put("channels.create", OBJECT);
    m.put("channels.rotate_secret", OBJECT);
    m.put("channels.update", OBJECT);

    m.put("contacts.cards", OBJECT);
    m.put("contacts.mood", OBJECT);

    // {items} with NO cursor. An object, however much it reads like a page —
    // and `tickets.time` and `macros.templates` are the same trap.
    m.put("conversations.linked", OBJECT);
    m.put("conversations.outcomes", OBJECT);
    m.put("conversations.record_outcome", OBJECT);
    m.put("conversations.return_to_bot", OBJECT);
    m.put("conversations.unread_counts", OBJECT);
    m.put("conversations.update_blocks", OBJECT);

    m.put("customer_card_endpoints.activate_secret", OBJECT);
    m.put("customer_card_endpoints.create", OBJECT);
    m.put("customer_card_endpoints.delete", OBJECT);
    m.put("customer_card_endpoints.list", ARRAY);
    m.put("customer_card_endpoints.rotate_secret", OBJECT);

    m.put("customer_cards.create", OBJECT);
    m.put("customer_cards.delete", OBJECT);
    m.put("customer_cards.list", ARRAY);
    m.put("customer_cards.rotate_secret", OBJECT);
    m.put("customer_cards.update", OBJECT);

    m.put("docs_sources.create", OBJECT);
    m.put("docs_sources.delete", OBJECT);
    // A POST that answers a bare array. One of two on the surface.
    m.put("docs_sources.discover", ARRAY);
    m.put("docs_sources.list", ARRAY);
    m.put("docs_sources.providers", ARRAY);
    m.put("docs_sources.sync", OBJECT);
    m.put("docs_sources.update", OBJECT);
    m.put("docs_sources.webhook", OBJECT);

    // A DELETE with a body: {restored: boolean}. The flag is the only way to
    // tell "your override is gone" from "there was never one".
    m.put("email_templates.delete", OBJECT);
    m.put("email_templates.list", ARRAY);
    m.put("email_templates.preview", OBJECT);
    m.put("email_templates.update", OBJECT);

    m.put("knowledge_gaps.draft", OBJECT);
    m.put("knowledge_gaps.list", ARRAY);
    m.put("knowledge_gaps.update", OBJECT);

    m.put("macros.actions", OBJECT);
    m.put("macros.get", OBJECT);
    m.put("macros.template", OBJECT);
    m.put("macros.templates", OBJECT);

    m.put("reports.attribution", ARRAY);
    m.put("reports.cohorts", OBJECT);
    m.put("reports.labels", OBJECT);
    m.put("reports.response_times", OBJECT);
    m.put("reports.sources", ARRAY);
    m.put("reports.teams", OBJECT);
    m.put("reports.theme_conversations", OBJECT);
    m.put("reports.themes", OBJECT);
    m.put("reports.voice", OBJECT);

    m.put("tickets.conversations", ARRAY);
    m.put("tickets.lineage", OBJECT);
    m.put("tickets.open_count", OBJECT);
    m.put("tickets.status_history", OBJECT);
    m.put("tickets.time", OBJECT);

    m.put("triggers.create", OBJECT);
    m.put("triggers.delete", OBJECT);
    m.put("triggers.list", PAGE);

    // A DELETE with a body: z.object({ ok: z.literal(true) }), and no
    // @HttpCode, so 200 and not the 204 the verb suggests.
    m.put("webhooks.delete", OBJECT);
    m.put("webhooks.create", OBJECT);
    m.put("webhooks.events", OBJECT);
    m.put("webhooks.list", ARRAY);
    m.put("webhooks.schema_versions", OBJECT);
    m.put("webhooks.set_schema_version", OBJECT);

    m.put("workspace.key_scopes", ARRAY);
    // z.array(workspaceListItemSchema) — a bare array, one entry for a key.
    m.put("workspace.mine", ARRAY);
    m.put("workspace.secrets", OBJECT);

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
