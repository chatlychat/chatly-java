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

  /**
   * Plan, seats, period and balance. {@code GET /v1/billing}
   *
   * <p>Note the path: the summary is the collection root, so {@code /v1/billing} is an object and
   * not a list of anything. Everything here needs {@code workspace:billing:read}, which is a
   * separate scope from the rest of the workspace — including
   * {@code chatly.ai().quota()}, which lives in the AI namespace and is gated on this same billing
   * permission.
   */
  public Map<String, Object> summary() {
    String path = "/v1/billing";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Invoices, as Stripe reported them. {@code GET /v1/billing/invoices}
   *
   * <p><strong>Cursor-paginated, and deliberately not a {@link Page}.</strong> Its envelope is
   * {@code {data, nextCursor}} — {@code data}, not {@code items} — because it predates the shared
   * pagination helper, and the difference is load-bearing for every client already reading it.
   *
   * <p>So there is no {@code invoicesAuto} here and {@link Page#fromDecoded} would reject the body:
   * a generic walker keyed on {@code items} would read an empty page forever and report "no
   * invoices" with a 200. Walk it yourself:
   *
   * <pre>{@code
   * String cursor = null;
   * do {
   *   Map<String, Object> page = chatly.billing().invoices(Query.of().cursor(cursor));
   *   List<?> rows = (List<?>) page.get("data");
   *   // …
   *   cursor = (String) page.get("nextCursor");
   * } while (cursor != null);
   * }</pre>
   *
   * @param query {@code cursor} and {@code limit}. A non-numeric {@code limit} is ignored rather
   *     than refused — the handler parses it and drops it when the parse fails.
   */
  public Map<String, Object> invoices(Query query) {
    String path = "/v1/billing/invoices";
    return object(transport.get(path, q(query)), path);
  }

  /** The first page of invoices. */
  public Map<String, Object> invoices() {
    return invoices(Query.of());
  }

  /**
   * A day-bucketed usage series. {@code GET /v1/billing/usage/daily}
   *
   * <p><strong>Dense</strong>: one entry per UTC day in {@code [from, to)}, zeros included, so the
   * array length is the day count and a gap in it would be a bug rather than a quiet day. The
   * window defaults to the current calendar month (UTC) and is capped at 365 days in the service.
   *
   * @param eventType required, and validated against the server's known meters — an unrecognised
   *     value is a 400 whose message lists the real ones, which makes it the cheapest way to
   *     discover them. Deliberately not duplicated as an enum here: a hardcoded list in an SDK
   *     goes stale the first time a meter is added and then rejects a value the API accepts.
   * @param query {@code from} and {@code to} as ISO instants
   */
  public Map<String, Object> usageDaily(String eventType, Query query) {
    String path = "/v1/billing/usage/daily";
    return object(
        transport.get(path, q(query).copy().set("eventType", required(eventType, "eventType"))),
        path);
  }

  /** Daily usage for the current calendar month. */
  public Map<String, Object> usageDaily(String eventType) {
    return usageDaily(eventType, Query.of());
  }

  /**
   * A whole-month rollup across every meter. {@code GET /v1/billing/usage/monthly}
   *
   * <p>Unlike {@link #usage} and {@link #usageDaily}, this one takes <strong>no
   * {@code eventType}</strong>: it returns one entry per known meter, always all of them, which is
   * what makes it comparable month to month even when a meter recorded nothing.
   *
   * <p>The window is always a whole calendar month, because that is what an invoice line is
   * denominated in. {@link #usage} is the route for an arbitrary range.
   *
   * @param query {@code month} as {@code YYYY-MM} (UTC), defaulting to the current month
   */
  public Map<String, Object> usageMonthly(Query query) {
    String path = "/v1/billing/usage/monthly";
    return object(transport.get(path, q(query)), path);
  }

  /** The current calendar month's rollup. */
  public Map<String, Object> usageMonthly() {
    return usageMonthly(Query.of());
  }
}
