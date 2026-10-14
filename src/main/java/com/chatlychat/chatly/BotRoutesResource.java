package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Bot routes — which assistant answers a conversation, and under what conditions.
 *
 * <p>Reached as {@code chatly.botRoutes()}.
 *
 * <p>The table is <strong>ordered and first-match-wins</strong>: {@link #list()} returns it in
 * evaluation order and {@code priority} is what changes that order. A route with no conditions
 * matches everything, so one of those sitting above the others is the usual reason a later rule
 * never fires — and {@link #resolve} is how you see that rather than guess at it.
 *
 * <p>Everything here is {@code triggers:read} / {@code triggers:write}, including
 * {@link #resolve}: it answers nothing a caller could not compute from the table plus the
 * evaluator, so it does not ask for write authority.
 */
public final class BotRoutesResource extends Resource {

  private static final String COLLECTION = "/v1/bot-routes";

  BotRoutesResource(Transport transport) {
    super(transport);
  }

  /**
   * The routing table, in evaluation order. {@code GET /v1/bot-routes} — a bare array.
   *
   * <p>Not paginated: a workspace keeps a handful of routes and the order is the whole point, so
   * there is nothing to page and no {@code listAuto}.
   */
  public List<Map<String, Object>> list() {
    return array(transport.get(COLLECTION, Query.of()), COLLECTION);
  }

  /**
   * Add a routing rule. {@code POST /v1/bot-routes}
   *
   * <p>Required: {@code botId} and {@code name}. Optional: {@code priority} (0-10,000),
   * {@code enabled}, {@code channelTypes}, {@code conditions} (at most 20), {@code brandId} and
   * {@code evaluate}.
   *
   * <p>{@code evaluate} — when the route is consulted — is <strong>defaulted by the database
   * column, not by the request schema</strong>. Omitting it gets the column's answer
   * ({@code conversation_start}); so a client that hardcodes a default here could disagree with the
   * server, and this SDK deliberately sends nothing when you send nothing.
   */
  public Map<String, Object> create(Map<String, Object> route) {
    return object(transport.post(COLLECTION, Query.of(), required(route, "route")), COLLECTION);
  }

  /**
   * Edit a routing rule. {@code PATCH /v1/bot-routes/{id}}
   *
   * <p>Partial — every field of {@link #create} is optional here, including {@code botId}. Sending
   * {@code conditions} replaces the whole array rather than merging into it, so read the route
   * first if you mean to add one condition.
   */
  public Map<String, Object> update(String routeId, Map<String, Object> changes) {
    String path = COLLECTION + "/" + seg(routeId, "routeId");
    return object(transport.patch(path, Query.of(), required(changes, "changes")), path);
  }

  /**
   * Remove a routing rule. {@code DELETE /v1/bot-routes/{id}}
   *
   * <p>Answers 200 with {@code {"deleted": true}}, not a 204. Conversations already routed by it
   * are untouched; this only changes what happens next.
   */
  public Map<String, Object> delete(String routeId) {
    String path = COLLECTION + "/" + seg(routeId, "routeId");
    return object(transport.delete(path, Query.of(), null), path);
  }

  /**
   * The fields and operators a condition may use. {@code GET /v1/bot-routes/fields}
   *
   * <p>An object — a static vocabulary, not a collection of rows — and the authoritative one: a
   * condition whose {@code field} is not in here never matches anything, and nothing about saving
   * it says so. Read this before building a rule from a field name you inferred from a webhook
   * payload.
   */
  public Map<String, Object> fields() {
    String path = COLLECTION + "/fields";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Dry-run the routing table: which bot answers, and why not the others.
   *
   * <p>{@code POST /v1/bot-routes/resolve}
   *
   * <p>A read dressed as a POST — the body is a hypothetical visitor, which is too much structure
   * for a query string — and it <strong>writes nothing</strong>: no conversation, no contact, no
   * stored context is read or created.
   *
   * <p>Three things come back and the third is the useful one: the selected route (or null), every
   * route with its verdict in evaluation order, and <strong>the event object the conditions were
   * actually evaluated against</strong>. Half of every routing bug is a field path that resolves to
   * nothing, and the only way to see that is to see the object.
   *
   * <p>{@code page} may carry {@code url} or {@code path} or both: the server derives whichever is
   * missing, so a rule keyed on {@code page.path} matches a body that sent only a URL. An
   * unparseable URL yields a null path rather than an error, because refusing the request would
   * make a malformed URL look like a routing problem.
   *
   * @param context the visitor to evaluate. Every key is optional — {@code resolve(Map.of())}
   *     evaluates an anonymous visitor on no page, which is a legitimate question and the fastest
   *     way to find a condition-less route sitting at the top of the table.
   */
  public Map<String, Object> resolve(Map<String, Object> context) {
    String path = COLLECTION + "/resolve";
    return object(transport.post(path, Query.of(), required(context, "context")), path);
  }
}
