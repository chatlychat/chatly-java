package com.chatlychat.chatly;

import java.util.Map;

/**
 * Webhook delivery history — what we tried to send you, and whether it landed.
 *
 * <p>Reached as {@code chatly.webhooks()}. For <em>verifying</em> a delivery that arrived at your
 * endpoint, see {@link Webhooks}, which is a static helper and needs no client.
 */
public final class WebhooksResource extends Resource {

  private static final String COLLECTION = "/v1/webhooks/deliveries";

  WebhooksResource(Transport transport) {
    super(transport);
  }

  /** One page of deliveries. {@code GET /v1/webhooks/deliveries} */
  public Page<Map<String, Object>> deliveries() {
    return deliveries(Query.of());
  }

  /** One page of deliveries, with query parameters. */
  public Page<Map<String, Object>> deliveries(Query query) {
    return page(COLLECTION, query);
  }

  /** Every delivery, pages walked for you. */
  public Pager<Map<String, Object>> deliveriesAuto() {
    return deliveriesAuto(Query.of());
  }

  /** Every delivery matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> deliveriesAuto(Query query) {
    return pager(COLLECTION, query);
  }

  /**
   * One delivery, including its request and response bodies.
   *
   * <p>{@code GET /v1/webhooks/deliveries/{id}}
   *
   * <p>This is where to look when a signature check fails: the stored request body is the exact
   * bytes that were signed, so comparing it to what your handler received tells you whether a proxy
   * rewrote the body in transit.
   */
  public Map<String, Object> delivery(String deliveryId) {
    String path = COLLECTION + "/" + seg(deliveryId, "deliveryId");
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Send a delivery again. {@code POST /v1/webhooks/deliveries/{id}/redeliver}
   *
   * <p>The replay arrives with a <strong>fresh</strong> {@code t} and a signature over it, so it
   * passes verification at your endpoint. It is a new delivery row, not a mutation of this one.
   */
  public Map<String, Object> redeliver(String deliveryId) {
    String path = COLLECTION + "/" + seg(deliveryId, "deliveryId") + "/redeliver";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }
}
