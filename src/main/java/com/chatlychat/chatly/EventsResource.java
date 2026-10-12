package com.chatlychat.chatly;

import java.util.Map;

/**
 * The customer-data platform event stream: what your users did, attached to their contact record.
 *
 * <p>Reached as {@code chatly.events()}.
 */
public final class EventsResource extends Resource {

  private static final String PATH = "/v1/cdp/events";

  EventsResource(Transport transport) {
    super(transport);
  }

  /**
   * Record an event against a contact. {@code POST /v1/cdp/events}
   *
   * @param contactId required
   * @param name required — the event name, e.g. {@code "checkout.completed"}
   * @param properties optional arbitrary payload; pass {@code null} for none
   */
  public Map<String, Object> track(
      String contactId, String name, Map<String, Object> properties) {
    Map<String, Object> payload = body();
    payload.put("contactId", required(contactId, "contactId"));
    payload.put("name", required(name, "name"));
    put(payload, "properties", properties);
    return object(transport.post(PATH, Query.of(), payload), PATH);
  }

  /** Record an event with no properties. */
  public Map<String, Object> track(String contactId, String name) {
    return track(contactId, name, null);
  }

  /**
   * Read events back. {@code GET /v1/cdp/events}
   *
   * <p>The same collection {@link #track} writes to. Without this, "did my tracking call actually
   * land?" has no answer outside the dashboard. Scope to one person with
   * {@code Query.of().add("contactId", id)}.
   */
  public Page<Map<String, Object>> list() {
    return list(Query.of());
  }

  /** One page of events, with query parameters. */
  public Page<Map<String, Object>> list(Query query) {
    return page(PATH, query);
  }

  /** Every event, pages walked for you. */
  public Pager<Map<String, Object>> listAuto() {
    return listAuto(Query.of());
  }

  /** Every event matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> listAuto(Query query) {
    return pager(PATH, query);
  }
}
