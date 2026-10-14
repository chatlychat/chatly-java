package com.chatlychat.chatly;

import java.util.Map;

/**
 * Triggers — event-driven rules: when <em>this</em> happens, do <em>that</em>.
 *
 * <p>Reached as {@code chatly.triggers()}.
 *
 * <p>A trigger is {@code eventType} plus {@code conditions} plus {@code actions}. The event names
 * are a customer-visible vocabulary persisted as strings, so the set a workspace can subscribe to
 * is the one the workflow builder offers; an event name nothing publishes saves fine and never
 * fires, which is exactly the failure this product has had before. If a trigger never runs, check
 * the name before you check the conditions.
 */
public final class TriggersResource extends Resource {

  private static final String COLLECTION = "/v1/triggers";

  TriggersResource(Transport transport) {
    super(transport);
  }

  /** One page of triggers. {@code GET /v1/triggers} */
  public Page<Map<String, Object>> list() {
    return list(Query.of());
  }

  /** One page of triggers, with query parameters. */
  public Page<Map<String, Object>> list(Query query) {
    return page(COLLECTION, query);
  }

  /** Every trigger, pages walked for you. */
  public Pager<Map<String, Object>> listAuto() {
    return listAuto(Query.of());
  }

  /** Every trigger matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> listAuto(Query query) {
    return pager(COLLECTION, query);
  }

  /**
   * Create <em>or update</em> a trigger. {@code POST /v1/triggers}
   *
   * <p>An upsert on a POST: pass {@code id} and it replaces that trigger, omit it and one is
   * created. That is why there is no PATCH here — and why a caller holding a stale {@code id} in a
   * config file overwrites rather than conflicts.
   *
   * <p>Required: {@code name}, {@code eventType}, {@code enabled}, and {@code actions} with between
   * 1 and 20 entries — a trigger that does nothing is refused rather than stored. {@code conditions}
   * is optional and capped at 50; an empty one means "every occurrence of this event".
   *
   * <p>Each action is {@code {type, params}} and each condition is {@code {field, op, value}} with
   * {@code op} one of {@code eq}, {@code neq}, {@code in}, {@code gt}, {@code lt},
   * {@code contains}, {@code matches}.
   */
  public Map<String, Object> create(Map<String, Object> trigger) {
    return object(
        transport.post(COLLECTION, Query.of(), required(trigger, "trigger")), COLLECTION);
  }

  /**
   * Delete a trigger. {@code DELETE /v1/triggers/{id}}
   *
   * <p>Answers 200 with an acknowledgement body rather than a 204. An in-flight action already
   * queued by this trigger still runs: deleting it stops future matches, not work in the queue.
   */
  public Map<String, Object> delete(String triggerId) {
    String path = COLLECTION + "/" + seg(triggerId, "triggerId");
    return object(transport.delete(path, Query.of(), null), path);
  }
}
