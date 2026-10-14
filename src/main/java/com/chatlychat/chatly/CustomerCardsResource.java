package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Customer cards — panels in the agent inbox whose contents come from <em>your</em> backend.
 *
 * <p>Reached as {@code chatly.customerCards()}.
 *
 * <p>A card is a url we fetch, signed with a secret you hold, rendered as blocks beside the
 * conversation. This resource configures them; {@code chatly.contacts().cards(contactId)} is the
 * call that actually fetches them for one person.
 *
 * <p>Read methods return header <strong>names</strong> and never their values, and no route returns
 * a stored signing secret. Two methods emit one — {@link #create} and {@link #rotateSecret} — each
 * exactly once.
 */
public final class CustomerCardsResource extends Resource {

  private static final String COLLECTION = "/v1/customer-cards";

  CustomerCardsResource(Transport transport) {
    super(transport);
  }

  /**
   * Every configured card. {@code GET /v1/customer-cards} — a bare array.
   *
   * <p>Header names only; no values, no secrets, no prefix of one. Not paginated: a workspace
   * configures at most 25 cards.
   */
  public List<Map<String, Object>> list() {
    return array(transport.get(COLLECTION, Query.of()), COLLECTION);
  }

  /**
   * Configure a card. {@code POST /v1/customer-cards}
   *
   * <p>Required: {@code key} ({@code [A-Za-z0-9_-]}, so it is safe in a url and a cache key),
   * {@code title}, and {@code url}.
   *
   * <p><strong>The response is the only one that carries {@code secret}</strong>, beside the card.
   * Store it before you discard the response; there is no reveal route, and recovering from a lost
   * secret means {@link #rotateSecret} and a deploy.
   *
   * <p>Two refusals that are easy to trip. The url is checked against the outbound guard — https
   * only, no embedded credentials, nothing resolving to a private, link-local or metadata address —
   * because <em>our</em> server fetches it. And a header named {@code x-chatly-*},
   * {@code content-type}, {@code host} and a few others is refused by name: those are how your
   * server knows the request is ours and which card's secret signed it, so a card may not set one.
   */
  public Map<String, Object> create(Map<String, Object> card) {
    return object(transport.post(COLLECTION, Query.of(), required(card, "card")), COLLECTION);
  }

  /**
   * Change a card. {@code PATCH /v1/customer-cards/{id}}
   *
   * <p>Partial and <strong>strict</strong>: an unknown key is a 400 rather than an ignored field.
   * An absent field is unchanged — but {@code headers: {}} is not absence, it <em>clears</em> every
   * header the card had, and the next fetch goes out without them.
   *
   * <p>{@code position} (0-1000) is accepted here and not at create: ordering is a later decision.
   */
  public Map<String, Object> update(String cardId, Map<String, Object> changes) {
    String path = COLLECTION + "/" + seg(cardId, "cardId");
    return object(transport.patch(path, Query.of(), required(changes, "changes")), path);
  }

  /**
   * Mint a new signing secret. {@code POST /v1/customer-cards/{id}/rotate-secret}
   *
   * <p>Shown once, and <strong>the previous secret stops verifying immediately</strong> — there is
   * no overlap. Deploy the new one to your endpoint before calling this, not after, or every fetch
   * in between fails signature verification at your end.
   *
   * <p>If you need an overlap, the card <em>endpoint</em> resource has one:
   * {@code chatly.customerCardEndpoints()} mints a pending secret that signs nothing until you
   * activate it. That is the rotation to use for a url you cannot redeploy atomically.
   */
  public Map<String, Object> rotateSecret(String cardId) {
    String path = COLLECTION + "/" + seg(cardId, "cardId") + "/rotate-secret";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }

  /**
   * Remove a card. {@code DELETE /v1/customer-cards/{id}}
   *
   * <p>Answers 200 with {@code {"ok": true}} rather than a 204, so this returns a map rather than
   * being {@code void}. The panel disappears from the inbox; your endpoint is not told.
   */
  public Map<String, Object> delete(String cardId) {
    String path = COLLECTION + "/" + seg(cardId, "cardId");
    return object(transport.delete(path, Query.of(), null), path);
  }
}
