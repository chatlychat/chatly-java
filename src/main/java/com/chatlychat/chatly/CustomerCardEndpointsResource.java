package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Per-url signing secrets for customer cards — and a rotation with an overlap window.
 *
 * <p>Reached as {@code chatly.customerCardEndpoints()}.
 *
 * <p>By default each card carries its own secret and every request to it carries
 * {@code x-chatly-card-id} so your verifier knows which to use. An <em>endpoint</em> changes that
 * for one url: one secret signs every card pointing at it, and the {@code x-chatly-card-id} header
 * is no longer sent. <strong>That is a change at your verifier, not a tidy-up</strong> — in both
 * directions, which is why {@link #delete} is as much a deploy as {@link #create}.
 *
 * <p>The reason to use it is {@link #rotateSecret} plus {@link #activateSecret}: a two-step
 * rotation where the new secret exists before it signs anything. The per-card rotation on
 * {@code chatly.customerCards()} has no such window.
 */
public final class CustomerCardEndpointsResource extends Resource {

  private static final String COLLECTION = "/v1/customer-card-endpoints";

  CustomerCardEndpointsResource(Transport transport) {
    super(transport);
  }

  /**
   * Configured endpoints. {@code GET /v1/customer-card-endpoints} — a bare array.
   *
   * <p>Never the secret, nor a prefix of it, nor a hash of it. {@code cardKeys} is the set of cards
   * pointing at each url, and an <strong>empty one means the secret signs nothing</strong> — an
   * endpoint left behind after its cards were re-pointed or deleted. Not paginated: one row per
   * distinct url, and a workspace has at most 25 cards.
   */
  public List<Map<String, Object>> list() {
    return array(transport.get(COLLECTION, Query.of()), COLLECTION);
  }

  /**
   * Give one card url its own signing secret. {@code POST /v1/customer-card-endpoints}
   *
   * <p>The body is {@code {"url": …}} and strict. Returns the endpoint with {@code secret} beside
   * it, once.
   *
   * <p><strong>From this call on, every request to that url is signed with THIS secret and carries
   * no {@code x-chatly-card-id}.</strong> Deploy your verifier before calling, not after.
   *
   * <p>Two refusals: a url no configured card uses is a 400 (there would be nothing to sign), and a
   * url that already has an endpoint is a 409 rather than a silent replacement.
   */
  public Map<String, Object> create(String url) {
    return object(
        transport.post(COLLECTION, Query.of(), Map.of("url", required(url, "url"))), COLLECTION);
  }

  /**
   * Mint the NEXT secret. {@code POST /v1/customer-card-endpoints/{id}/rotate-secret}
   *
   * <p>Shown once, with {@code active: false}: <strong>it signs nothing yet</strong>. The current
   * secret keeps signing until {@link #activateSecret}, which is the whole point — your verifier
   * can accept both for as long as the deploy takes.
   *
   * <p>Calling this again before activating <em>replaces</em> the pending secret rather than
   * stacking a second one, so a retry is safe and a second deploy is not.
   */
  public Map<String, Object> rotateSecret(String endpointId) {
    String path = COLLECTION + "/" + seg(endpointId, "endpointId") + "/rotate-secret";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }

  /**
   * Promote the pending secret. {@code POST /v1/customer-card-endpoints/{id}/activate-secret}
   *
   * <p>It starts signing now and the previous one is <strong>gone</strong>, not deprecated. A 409
   * when nothing is pending — which is also the answer to a double-activate, so a retry cannot
   * silently rotate twice.
   */
  public Map<String, Object> activateSecret(String endpointId) {
    String path = COLLECTION + "/" + seg(endpointId, "endpointId") + "/activate-secret";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }

  /**
   * Remove the endpoint secret. {@code DELETE /v1/customer-card-endpoints/{id}}
   *
   * <p>Signing reverts to the per-card secrets and requests start carrying
   * {@code x-chatly-card-id} again — signed by the <em>leading</em> card of the group, which may
   * not be the card you think of as primary. Your verifier has to be ready for that before you
   * call: this is a cutover, not a cleanup.
   */
  public Map<String, Object> delete(String endpointId) {
    String path = COLLECTION + "/" + seg(endpointId, "endpointId");
    return object(transport.delete(path, Query.of(), null), path);
  }
}
