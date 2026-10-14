package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Macros — saved replies and action bundles an agent can apply in one click.
 *
 * <p>Reached as {@code chatly.macros()}.
 */
public final class MacrosResource extends Resource {

  private static final String COLLECTION = "/v1/macros";

  MacrosResource(Transport transport) {
    super(transport);
  }

  /**
   * Every macro. {@code GET /v1/macros}
   *
   * <p>A bare array, not a page: the route is pagination-exempt because a macro picker has to
   * autocomplete over the whole set. There is deliberately no {@code listAuto} — there is nothing
   * to paginate.
   */
  public List<Map<String, Object>> list() {
    return list(Query.of());
  }

  /** Every macro, with query parameters (e.g. a scope filter). */
  public List<Map<String, Object>> list(Query query) {
    return array(transport.get(COLLECTION, q(query)), COLLECTION);
  }

  /**
   * Create a macro. {@code POST /v1/macros}
   *
   * <p>Required keys: {@code name}, {@code scope}, and <em>either</em> a {@code body} or a non-empty
   * {@code actions} array. When {@code scope} is {@code "team"}, {@code teamId} is required too.
   * The server enforces all of that; this method checks only that the map is present, because
   * duplicating a conditional schema here is how an SDK ends up rejecting a payload the API would
   * have accepted.
   */
  public Map<String, Object> create(Map<String, Object> macro) {
    return object(transport.post(COLLECTION, Query.of(), required(macro, "macro")), COLLECTION);
  }

  /**
   * Update a macro. {@code PATCH /v1/macros/{id}}
   *
   * <p>A partial update: send only the keys you want changed. Omitting a key leaves it alone;
   * setting it to {@code null} is not the same thing and is not how you clear a field.
   */
  public Map<String, Object> update(String macroId, Map<String, Object> changes) {
    String path = COLLECTION + "/" + seg(macroId, "macroId");
    return object(transport.patch(path, Query.of(), required(changes, "changes")), path);
  }

  /**
   * Delete a macro. {@code DELETE /v1/macros/{id}}
   *
   * <p>Returns nothing: the route answers 204. The method is {@code void} rather than returning an
   * empty map, so a caller is not invited to inspect a body that does not exist.
   */
  public void delete(String macroId) {
    String path = COLLECTION + "/" + seg(macroId, "macroId");
    transport.delete(path, Query.of(), null);
  }

  /**
   * One macro by id. {@code GET /v1/macros/{id}}
   *
   * <p><strong>A personal macro belonging to a human agent is a 404 here, not a 403.</strong> The
   * {@code macros} table has FORCE row-level security with a personal-scope clause on top of the
   * workspace one: a row with {@code scope = 'personal'} is visible only when
   * {@code app.current_user_id} equals its owner, and for an API key that setting is the key's own
   * id. So a key reads workspace- and team-scoped macros and cannot see anyone's private ones —
   * including through {@link #list()}, which is why a macro visible in the dashboard can be absent
   * from this SDK's view of the same workspace.
   */
  public Map<String, Object> get(String macroId) {
    String path = COLLECTION + "/" + seg(macroId, "macroId");
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * The actions a macro may perform. {@code GET /v1/macros/actions}
   *
   * <p>An object: {@code {limit, actions}} — a fixed vocabulary of eleven entries defined in code,
   * plus the per-macro action limit. This is the authoritative list; the dashboard reads the same
   * route rather than keeping its own copy, and an action {@code type} outside it is a 400 from
   * {@link #create}.
   *
   * <p>It also answers a question the create route cannot: a macro referencing an action that has
   * since been <em>removed</em> from the registry fails at run time with a 422 naming the action
   * and its index. Checking against this list is how you find that before an agent does.
   */
  public Map<String, Object> actions() {
    String path = COLLECTION + "/actions";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Installable starter templates, in a lite shape. {@code GET /v1/macros/templates}
   *
   * <p>An object, {@code {items, total}}, and <strong>{@code total} is just
   * {@code items.length}</strong> over a frozen in-process catalog — not a hint at a second page.
   * There is no cursor and nothing to walk.
   *
   * <p>Rows omit the macro body; {@link #template} serves one in full. Note these are templates,
   * not macros: nothing here exists in the workspace until it is installed, and installing is a
   * {@code macros:write} route that an API key cannot reach.
   */
  public Map<String, Object> templates() {
    String path = COLLECTION + "/templates";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * One template in full, body included. {@code GET /v1/macros/templates/{slug}}
   *
   * <p>By slug, not id — a template is a code-level constant and has no database row, so there is
   * no id to hold. Read it to copy the body into your own {@link #create} call, which is the only
   * way to get a template into a workspace from an API key.
   */
  public Map<String, Object> template(String slug) {
    String path = COLLECTION + "/templates/" + seg(slug, "slug");
    return object(transport.get(path, Query.of()), path);
  }
}
