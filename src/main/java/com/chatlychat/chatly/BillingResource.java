package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Plans and metered usage.
 *
 * <p>Reached as {@code chatly.billing()}.
 */
public final class BillingResource extends Resource {

  BillingResource(Transport transport) {
    super(transport);
  }

  /** The plans available to this workspace. {@code GET /v1/billing/plans} — a bare array. */
  public List<Map<String, Object>> plans() {
    String path = "/v1/billing/plans";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * Usage for one metered event type. {@code GET /v1/billing/usage?eventType=…}
   *
   * @param eventType <strong>required</strong>, and must be one of the server's known usage event
   *     types — an unrecognised value is a 400, not an empty result. There is no "all types" call;
   *     if you want several, ask for each. The list is in the dashboard under Settings → Billing →
   *     Usage, and it is deliberately not duplicated here: a hardcoded enum in an SDK goes stale
   *     the first time a meter is added and then rejects a value the API accepts.
   */
  public Map<String, Object> usage(String eventType) {
    return usage(eventType, Query.of());
  }

  /** Usage for one metered event type, with extra query parameters (a date range). */
  public Map<String, Object> usage(String eventType, Query query) {
    String path = "/v1/billing/usage";
    return object(
        transport.get(path, q(query).copy().set("eventType", required(eventType, "eventType"))),
        path);
  }
}
