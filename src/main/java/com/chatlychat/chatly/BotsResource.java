package com.chatlychat.chatly;

import java.time.Instant;
import java.util.Map;

/**
 * Bots — the configured AI personalities, and the customer's own push-delivered agents.
 *
 * <p>Reached as {@code chatly.bots()}.
 *
 * <p><strong>Two kinds of row live here and the difference runs through every method.</strong> A
 * <em>Chatly persona</em> is a prompt, a model and a tool list that we run. An <em>external
 * agent</em> ({@code type: "external"}) is a webhook we POST a signed payload to and wait for your
 * answer — your code, your infrastructure. {@code botType} on each row is the discriminator, and
 * the delivery methods ({@link #deliveries}, {@link #delivery}, {@link #redeliver},
 * {@link #testDelivery}) mean nothing for a persona: it has no endpoint to deliver to.
 */
public final class BotsResource extends Resource {

  private static final String COLLECTION = "/v1/bots";

  /** The server's bounds on {@link #analytics}'s {@code ?days} window. */
  private static final int MIN_DAYS = 1;

  private static final int MAX_DAYS = 365;

  BotsResource(Transport transport) {
    super(transport);
  }

  /** One page of bots. {@code GET /v1/bots} */
  public Page<Map<String, Object>> list() {
    return list(Query.of());
  }

  /** One page of bots, with query parameters. */
  public Page<Map<String, Object>> list(Query query) {
    return page(COLLECTION, query);
  }

  /**
   * Every bot, pages walked for you.
   *
   * <p>Genuinely cursor-paginated, unlike most of this product's small configuration lists — a
   * workspace can accumulate personas per brand and per language, so the route pages rather than
   * capping.
   */
  public Pager<Map<String, Object>> listAuto() {
    return listAuto(Query.of());
  }

  /** Every bot matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> listAuto(Query query) {
    return pager(COLLECTION, query);
  }

  /** One bot by id. {@code GET /v1/bots/{id}} */
  public Map<String, Object> get(String botId) {
    String path = COLLECTION + "/" + seg(botId, "botId");
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Create a bot. {@code POST /v1/bots}
   *
   * <p>The body is a union of two disjoint shapes and <strong>neither half is strict</strong>, so a
   * field belonging to the other one is <em>dropped</em> rather than refused: send a persona's
   * {@code systemPrompt} alongside {@code type: "external"} and you get a 201 and a bot without the
   * prompt. There is no error to catch — check the returned row.
   *
   * <p>A persona needs {@code name} and a {@code systemPrompt} of at least 20 characters. An
   * external agent needs {@code type: "external"} and its webhook configuration, and the response
   * is <strong>the only one that ever carries its two credentials</strong> — a signing secret and a
   * {@code cb_} token, each shown once and unrecoverable afterwards. Store them before you log the
   * response away.
   */
  public Map<String, Object> create(Map<String, Object> bot) {
    return object(transport.post(COLLECTION, Query.of(), required(bot, "bot")), COLLECTION);
  }

  /**
   * Change a persona. {@code PATCH /v1/bots/{id}}
   *
   * <p>Partial, and it must contain at least one field — an empty map is a 400 ("No fields to
   * update") rather than a no-op 200. For an external agent's webhook settings use
   * {@link #updateExternal} instead; this route edits the prompt-and-model side.
   */
  public Map<String, Object> update(String botId, Map<String, Object> changes) {
    String path = COLLECTION + "/" + seg(botId, "botId");
    return object(transport.patch(path, Query.of(), required(changes, "changes")), path);
  }

  /**
   * One bot's performance. {@code GET /v1/bots/{id}/analytics}
   *
   * <p>Latency is p50/p95/max and never a mean, deliberately: one 30-second timeout drags the mean
   * of a hundred 200ms calls to 498ms, a figure that describes neither population.
   *
   * <p><strong>A {@code null} rate is not zero.</strong> Three fields belong to only one of the two
   * bot kinds and read {@code null} for the other; {@code botType} tells you which you are looking
   * at.
   *
   * @param days 1 to 365. Checked here because the server refuses {@code from}, {@code to},
   *     {@code weeks} and {@code date} <em>by name</em> rather than defaulting a window — this
   *     report takes {@code days} and nothing else, and a non-numeric value is a 400 instead of a
   *     quiet default.
   */
  public Map<String, Object> analytics(String botId, int days) {
    if (days < MIN_DAYS || days > MAX_DAYS) {
      throw new IllegalArgumentException(
          "analytics: the API accepts ?days=" + MIN_DAYS + "-" + MAX_DAYS + ", got " + days);
    }
    return analytics(botId, Query.of().set("days", days));
  }

  /** As {@link #analytics(String, int)}, with the query you build yourself. */
  public Map<String, Object> analytics(String botId, Query query) {
    String path = COLLECTION + "/" + seg(botId, "botId") + "/analytics";
    return object(transport.get(path, q(query)), path);
  }

  // ------------------------------------------------------------ deliveries

  /**
   * An external agent's delivery history. {@code GET /v1/bots/{id}/deliveries}
   *
   * <p>Newest first: status, attempt count, latency and what your endpoint answered. Narrow with
   * {@code Query.of().add("status", …)}.
   *
   * <p><strong>A list row does not carry the signed payload.</strong> A page of fifty would be
   * megabytes of transcript, so the body is only on {@link #delivery}. That is the method to reach
   * for when a signature check failed at your end — the stored request body is the exact bytes that
   * were signed.
   */
  public Page<Map<String, Object>> deliveries(String botId) {
    return deliveries(botId, Query.of());
  }

  /** One page of deliveries, with query parameters. */
  public Page<Map<String, Object>> deliveries(String botId, Query query) {
    return page(COLLECTION + "/" + seg(botId, "botId") + "/deliveries", query);
  }

  /** Every delivery for one bot, pages walked for you. */
  public Pager<Map<String, Object>> deliveriesAuto(String botId) {
    return deliveriesAuto(botId, Query.of());
  }

  /** Every delivery matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> deliveriesAuto(String botId, Query query) {
    return pager(COLLECTION + "/" + seg(botId, "botId") + "/deliveries", query);
  }

  /**
   * One delivery, with the exact body that was signed and POSTed.
   *
   * <p>{@code GET /v1/bots/{id}/deliveries/{deliveryId}}
   *
   * <p>Both ids are needed even though the delivery id is unique: the route is scoped under its bot
   * so that a delivery id from another agent cannot be read by guessing.
   */
  public Map<String, Object> delivery(String botId, String deliveryId) {
    String path =
        COLLECTION + "/" + seg(botId, "botId") + "/deliveries/" + seg(deliveryId, "deliveryId");
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Queue one recorded delivery again, from attempt 0.
   *
   * <p>{@code POST /v1/bots/{id}/deliveries/{deliveryId}/redeliver}
   *
   * <p><strong>The same bytes, including the same {@code eventId}.</strong> So if your handler
   * dedupes on {@code eventId} — and it should — it will <em>discard</em> this replay when you have
   * already processed that id. That is correct on your side; a replay is for a delivery you never
   * successfully processed, not a way to make your handler run twice.
   *
   * <p>The retry ladder starts over, and this creates a new attempt chain rather than mutating the
   * recorded one.
   */
  public Map<String, Object> redeliver(String botId, String deliveryId) {
    String path =
        COLLECTION
            + "/"
            + seg(botId, "botId")
            + "/deliveries/"
            + seg(deliveryId, "deliveryId")
            + "/redeliver";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }

  /**
   * POST a real-shaped, really-signed test payload at the agent's URL and report what came back.
   *
   * <p>{@code POST /v1/bots/{id}/test-delivery}
   *
   * <p><strong>Writes nothing</strong>: no ledger row, no conversation, no retry. It is the first
   * call to make when building an external agent, because it answers the question the ledger cannot
   * — whether your endpoint is reachable and whether your signature check accepts ours. The result
   * includes up to 2,000 characters of your response body, the signature header we sent, and the
   * exact payload that was signed.
   *
   * <p>The payload carries {@code "test": true} and all-zero UUIDs. Acknowledge it with a 2xx and
   * <em>do not reply</em>: a reply would target a conversation that does not exist.
   */
  public Map<String, Object> testDelivery(String botId) {
    String path = COLLECTION + "/" + seg(botId, "botId") + "/test-delivery";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }

  // -------------------------------------------------------------- external

  /**
   * Change an external agent's webhook, timeout or fallback copy.
   *
   * <p>{@code PATCH /v1/bots/{id}/external}
   *
   * <p>Partial, and at least one field is required. Accepts {@code webhookUrl},
   * {@code timeoutSeconds} (1-60), {@code fallbackMessage} and {@code canStartConversations}.
   *
   * <p>{@code canStartConversations} is listed explicitly by the server rather than inherited from
   * the create schema, because the create schema defaults it to {@code false} — and a default on a
   * PATCH field turns "leave it alone" into "revoke it" for every caller that sends some other
   * field. Here, absent means unchanged.
   */
  public Map<String, Object> updateExternal(String botId, Map<String, Object> changes) {
    String path = COLLECTION + "/" + seg(botId, "botId") + "/external";
    return object(transport.patch(path, Query.of(), required(changes, "changes")), path);
  }

  /**
   * Issue a new signing secret for an external agent and return it once — a hard cut.
   *
   * <p>{@code POST /v1/bots/{id}/external/rotate-secret}, with the body {@code {}}.
   *
   * <p><strong>Without {@code activateAt} there is no overlap window.</strong> The new secret signs
   * every delivery from the moment this returns and the old one signs none, so deploy the new one
   * to your verifier first or you will reject every delivery in between. If you cannot redeploy
   * atomically, use {@link #rotateExternalSecret(String, Instant)} instead.
   *
   * @return {@code signingSecret} (shown once) and {@code activatesAt}, which is {@code null} here:
   *     the new secret is already signing.
   */
  public Map<String, Object> rotateExternalSecret(String botId) {
    String path = COLLECTION + "/" + seg(botId, "botId") + "/external/rotate-secret";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }

  /**
   * Issue a new signing secret now, but keep signing with the old one until {@code activateAt}.
   *
   * <p>{@code POST /v1/bots/{id}/external/rotate-secret}, with the body
   * {@code {"activateAt": "<instant>"}}. The instant is sent as {@link Instant#toString()}, which is
   * always UTC with a {@code Z}.
   *
   * <p>Until {@code activateAt} every delivery is still signed with the <em>old</em> secret; from
   * then on, with the new one. There is exactly one signature per delivery, so during the window
   * your verifier should hold both secrets and accept a delivery that verifies against either, then
   * drop the old one once the switch has passed.
   *
   * <p>The server refuses an instant more than 168 hours ahead of <em>its</em> clock with a 400;
   * that bound is deliberately not re-checked here. An instant already in the past is a hard cut,
   * not an error. So is a deferred rotation on an agent that has no usable secret today. A
   * {@code null} {@code activateAt} is the same request as {@link #rotateExternalSecret(String)}.
   *
   * @return {@code signingSecret} (shown once) and {@code activatesAt}: the instant the switch
   *     happens, or {@code null} when the new secret is already signing (a hard cut).
   */
  public Map<String, Object> rotateExternalSecret(String botId, Instant activateAt) {
    if (activateAt == null) {
      return rotateExternalSecret(botId);
    }
    String path = COLLECTION + "/" + seg(botId, "botId") + "/external/rotate-secret";
    return object(
        transport.post(path, Query.of(), Map.of("activateAt", activateAt.toString())), path);
  }
}
