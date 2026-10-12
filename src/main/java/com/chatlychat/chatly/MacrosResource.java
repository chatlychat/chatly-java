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
}
