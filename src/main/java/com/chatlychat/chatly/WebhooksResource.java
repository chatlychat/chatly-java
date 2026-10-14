package com.chatlychat.chatly;

import java.util.List;
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

  // ------------------------------------------------------------ endpoints
  //
  // The subscriptions themselves, as opposed to the deliveries above.
  //
  // DELETE /v1/webhooks/{id} is {@link #delete} below. It was absent from this
  // class while the route was absent from the shared manifest, and the escape
  // hatch was the documented workaround; both are fixed.

  /**
   * Webhook subscriptions. {@code GET /v1/webhooks} — a bare array.
   *
   * <p>Not paginated: typically fewer than 50 per workspace. No row carries the signing secret;
   * {@link #create} is the only response that ever does.
   */
  public List<Map<String, Object>> endpoints() {
    String path = "/v1/webhooks";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * Subscribe an endpoint. {@code POST /v1/webhooks}
   *
   * <p>Required: {@code url} and {@code eventTypes} with at least one entry — a subscription to
   * nothing is refused rather than stored. Optional: {@code description} (280 chars) and
   * {@code schemaVersion}.
   *
   * <p><strong>This is the only response that carries {@code secret}.</strong> There is no reveal
   * route and no rotation route: losing it means deleting the subscription and creating another, so
   * store it before you discard the response.
   *
   * <p>{@code eventTypes} is validated against the platform's enum, so take the values from
   * {@link #events()} rather than from a dashboard screenshot — the picker and this validation were
   * once separately hardcoded and the picker offered three events the platform does not emit.
   *
   * <p>{@code schemaVersion} is <strong>refused when unrecognised here</strong>, while the dispatch
   * path resolves an unknown version to the latest. Loud on the write, generous on the read: a typo
   * here would otherwise be an integration pinned to nothing that goes quiet with no error
   * anywhere.
   */
  public Map<String, Object> create(Map<String, Object> endpoint) {
    String path = "/v1/webhooks";
    return object(transport.post(path, Query.of(), required(endpoint, "endpoint")), path);
  }

  /**
   * Remove a subscription. {@code DELETE /v1/webhooks/{id}}
   *
   * <p>Answers 200 with {@code {"ok": true}}, not a 204 — hence a return value rather than
   * {@code void}. {@code integrations:write}, the same scope as {@link #create}, on the argument
   * that route already makes: a key that can add a delivery target can already do the thing with
   * consequences.
   *
   * <p><strong>This was missing {@code @AllowApiKey()} until 2026-10-14</strong> while create,
   * the schema-version patch and every GET here had it — so a key could add a subscription and had
   * no way to remove one. The asymmetry was worse than closing the surface to keys would have been:
   * a deploy pipeline that registers an endpoint per environment leaked one into the customer's
   * workspace on every run with nothing able to clean up.
   *
   * <p>The workaround recommended in the meantime — repointing the endpoint at a dead url — is
   * <em>not</em> equivalent and should be undone where it was followed: the subscription stays
   * alive, every event still attempts delivery, and the delivery log fills with failures. Deleting
   * it is the only thing that stops the attempts.
   */
  public Map<String, Object> delete(String endpointId) {
    String path = "/v1/webhooks/" + seg(endpointId, "endpointId");
    return object(transport.delete(path, Query.of(), null), path);
  }

  /**
   * The subscribable event list. {@code GET /v1/webhooks/events}
   *
   * <p>An object — {@code {"eventTypes": [...]}} — static and workspace-independent. It exists so a
   * picker and the create-route validation cannot drift apart, which is exactly what they did when
   * each had its own hardcoded copy.
   */
  public Map<String, Object> events() {
    String path = "/v1/webhooks/events";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * The published schema versions, oldest first. {@code GET /v1/webhooks/schema-versions}
   *
   * <p>An object: {@code latest}, plus a {@code versions} array where each entry carries
   * {@code isLatest}, {@code isDeprecated} and the {@code eventTypes} that version covers. Read it
   * before pinning — the point is to see what a version <em>contains</em>, because a pin to an
   * older version silently excludes events added after it.
   */
  public Map<String, Object> schemaVersions() {
    String path = "/v1/webhooks/schema-versions";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Pin one endpoint to a schema version. {@code PATCH /v1/webhooks/{id}/schema-version}
   *
   * <p>The response carries {@code missingEventTypes}: the events this subscription asked for that
   * the pinned version does not define, <strong>by name and not as a count</strong>. A non-empty
   * list means those events will not be delivered to this endpoint while the pin stands — read it,
   * rather than treating the 200 as confirmation that nothing changed.
   *
   * @param schemaVersion a dated version id from {@link #schemaVersions()}. An unrecognised value
   *     is a 400.
   */
  public Map<String, Object> setSchemaVersion(String endpointId, String schemaVersion) {
    String path = "/v1/webhooks/" + seg(endpointId, "endpointId") + "/schema-version";
    return object(
        transport.patch(
            path,
            Query.of(),
            Map.of("schemaVersion", required(schemaVersion, "schemaVersion"))),
        path);
  }
}
