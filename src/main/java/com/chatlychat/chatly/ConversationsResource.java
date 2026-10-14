package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Conversations and the messages in them.
 *
 * <p>Reached as {@code chatly.conversations()}.
 */
public final class ConversationsResource extends Resource {

  private static final String COLLECTION = "/v1/conversations";

  ConversationsResource(Transport transport) {
    super(transport);
  }

  /** One page of conversations. {@code GET /v1/conversations} */
  public Page<Map<String, Object>> list() {
    return list(Query.of());
  }

  /** One page of conversations, with query parameters. */
  public Page<Map<String, Object>> list(Query query) {
    return page(COLLECTION, query);
  }

  /** Every conversation, pages walked for you. */
  public Pager<Map<String, Object>> listAuto() {
    return listAuto(Query.of());
  }

  /** Every conversation matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> listAuto(Query query) {
    return pager(COLLECTION, query);
  }

  /** One conversation by id. {@code GET /v1/conversations/{id}} */
  public Map<String, Object> get(String conversationId) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId");
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * One page of a thread's messages. {@code GET /v1/conversations/{id}/messages}
   *
   * <p>Read the thread before replying to it. This is the call an "understand, then answer"
   * integration needs and the one most often hand-rolled because its existence is easy to miss.
   */
  public Page<Map<String, Object>> messages(String conversationId) {
    return messages(conversationId, Query.of());
  }

  /** One page of a thread's messages, with query parameters. */
  public Page<Map<String, Object>> messages(String conversationId, Query query) {
    return page(COLLECTION + "/" + seg(conversationId, "conversationId") + "/messages", query);
  }

  /** A whole thread, pages walked for you. */
  public Pager<Map<String, Object>> messagesAuto(String conversationId, Query query) {
    return pager(COLLECTION + "/" + seg(conversationId, "conversationId") + "/messages", query);
  }

  /**
   * Post a message into a conversation. {@code POST /v1/conversations/{id}/messages}
   *
   * @param body the message text. The server accepts an empty string (a message can be
   *     attachment-only), so this method does not reject one — but if you did not mean to send
   *     empty text, nothing downstream will tell you.
   */
  public Map<String, Object> send(String conversationId, String body) {
    return send(conversationId, Map.of("body", required(body, "body")));
  }

  /**
   * Post a message with the full payload. {@code POST /v1/conversations/{id}/messages}
   *
   * <p>Use this for anything beyond plain text: {@code kind} ({@code text}, {@code rich},
   * {@code note}, {@code system_event}), attachments, or blocks. {@code body} is the only required
   * key.
   */
  public Map<String, Object> send(String conversationId, Map<String, Object> message) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/messages";
    required(message, "message");
    if (!message.containsKey("body")) {
      throw new IllegalArgumentException("send: the message map must contain a `body` key");
    }
    return object(transport.post(path, Query.of(), message), path);
  }

  /**
   * Conversations nobody has answered. {@code GET /v1/conversations/unattended}
   *
   * <p>A page envelope like any other list, plus a {@code total} — reachable as
   * {@link Page#total()} — which is the number waiting across <em>all</em> pages, not on this one.
   * That is the figure to alert on.
   */
  public Page<Map<String, Object>> unattended() {
    return unattended(Query.of());
  }

  /** Conversations nobody has answered, with query parameters. */
  public Page<Map<String, Object>> unattended(Query query) {
    return page(COLLECTION + "/unattended", query);
  }

  /**
   * Every unanswered conversation, pages walked for you.
   *
   * <p>The route is genuinely cursor-paginated — it was briefly absent from the shared manifest's
   * paged list, which is why this arrived after the single-page form. Note that walking the whole
   * chain discards each page's {@code total}; if the number waiting is what you are alerting on,
   * read {@link #unattended()} and take {@link Page#total()} rather than counting the rows this
   * yields.
   */
  public Pager<Map<String, Object>> unattendedAuto() {
    return unattendedAuto(Query.of());
  }

  /** Every unanswered conversation matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> unattendedAuto(Query query) {
    return pager(COLLECTION + "/unattended", query);
  }

  /**
   * Who or what is currently handling this conversation.
   *
   * <p>{@code GET /v1/conversations/{id}/agent-handling}
   */
  public Map<String, Object> agentHandling(String conversationId) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/agent-handling";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Claim or release a conversation as an automated agent.
   *
   * <p>{@code PUT /v1/conversations/{id}/agent-handling}
   *
   * <p><strong>This route requires an API key linked to a machine user.</strong> A key that is not
   * linked — which is every key by default — gets a 403 ({@link
   * com.chatlychat.chatly.errors.PermissionException}) no matter what scopes it has, and so does a
   * human session. Link one with {@code PUT /v1/api-keys/{id}/machine-user} (dashboard: Settings →
   * API keys → Link machine user) before calling this. It is the only method in this SDK with a
   * precondition beyond scopes, and it is here rather than omitted because it is the entry point for
   * a bring-your-own-agent integration.
   *
   * @param state the handling state to set, e.g. {@code "agent"} or {@code "human"}
   */
  public Map<String, Object> setAgentHandling(String conversationId, String state) {
    return setAgentHandling(conversationId, Map.of("state", required(state, "state")));
  }

  /** As {@link #setAgentHandling(String, String)}, with the full payload. */
  public Map<String, Object> setAgentHandling(String conversationId, Map<String, Object> payload) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/agent-handling";
    return object(transport.put(path, Query.of(), required(payload, "payload")), path);
  }

  /**
   * Draft replies an AI agent has proposed but not sent.
   *
   * <p>{@code GET /v1/conversations/{id}/generated-replies}
   *
   * <p>Returns a bare array, not a page: the route is pagination-exempt because the set is bounded
   * by the approval window. There is no {@code generatedRepliesAuto}.
   */
  public List<Map<String, Object>> generatedReplies(String conversationId) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/generated-replies";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * Submit a reply for approval, rather than sending it.
   *
   * <p>{@code POST /v1/conversations/{id}/generated-replies}
   *
   * <p>The difference from {@link #send}: this one creates a draft a human can approve, edit or
   * reject. Use it when your agent should not speak to a customer unreviewed.
   */
  public Map<String, Object> generateReply(String conversationId, String body) {
    return generateReply(conversationId, Map.of("body", required(body, "body")));
  }

  /** As {@link #generateReply(String, String)}, with the full payload. */
  public Map<String, Object> generateReply(String conversationId, Map<String, Object> payload) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/generated-replies";
    return object(transport.post(path, Query.of(), required(payload, "payload")), path);
  }

  /**
   * Messages queued to send later.
   *
   * <p>{@code GET /v1/conversations/{id}/scheduled-messages}
   *
   * <p>A bare array; the route is pagination-exempt.
   */
  public List<Map<String, Object>> scheduledMessages(String conversationId) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/scheduled-messages";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * Queue a message to send at a future time.
   *
   * <p>{@code POST /v1/conversations/{id}/scheduled-messages}
   *
   * @param sendAt an ISO-8601 instant. {@code java.time.Instant.toString()} produces exactly the
   *     form the server accepts; a local date-time without an offset does not, and will be rejected
   *     as a validation error rather than guessed at.
   */
  public Map<String, Object> scheduleMessage(String conversationId, String body, String sendAt) {
    return scheduleMessage(
        conversationId,
        Map.of("body", required(body, "body"), "sendAt", required(sendAt, "sendAt")));
  }

  /** As {@link #scheduleMessage(String, String, String)}, with the full payload. */
  public Map<String, Object> scheduleMessage(String conversationId, Map<String, Object> payload) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/scheduled-messages";
    return object(transport.post(path, Query.of(), required(payload, "payload")), path);
  }

  /**
   * Cancel a queued message.
   *
   * <p>{@code DELETE /v1/conversations/scheduled-messages/{scheduledId}}
   *
   * <p>Note the path: the scheduled-message id is globally addressable, so no conversation id is
   * needed here even though creating one required it.
   */
  public Map<String, Object> cancelScheduled(String scheduledMessageId) {
    String path =
        COLLECTION + "/scheduled-messages/" + seg(scheduledMessageId, "scheduledMessageId");
    return object(transport.delete(path, Query.of(), null), path);
  }

  /**
   * Linked and merged conversations for this thread. {@code GET /v1/conversations/{id}/linked}
   *
   * <p><strong>An object with an {@code items} array and no cursor — not a page.</strong> It looks
   * like one in the raw JSON, which is exactly why this says so: there is no {@code linkedAuto},
   * and feeding this body to a paginator would walk one page and call it the end of a list that was
   * never a list.
   */
  public Map<String, Object> linked(String conversationId) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/linked";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Unread badge counts. {@code GET /v1/conversations/unread-counts}
   *
   * <p><strong>Nearly useless from an API key, and it answers 200 anyway — which is why it is
   * here.</strong> The route computes <em>per-user</em> state from the caller's
   * {@code membership.userId}, and for a key that is the key's own id: a principal that has never
   * opened a conversation, so every count is 0 and will stay 0 however busy the workspace is. It
   * is implemented rather than omitted because it does not fail — it returns a valid, confidently
   * wrong answer — and a method with this note attached is better than an integrator discovering
   * that for themselves. For "what is waiting", use {@link #unattended()} and read
   * {@link Page#total()}.
   *
   * <p>If you do read it: four scalars plus three bucket arrays, not a page and not a bare array.
   * {@code mentions}, {@code participating} and {@code unattended} are <em>subsets</em> of
   * {@code unread}, so summing them double-counts. {@code byLabel} is capped at 50 buckets, ordered
   * by count.
   */
  public Map<String, Object> unreadCounts() {
    String path = COLLECTION + "/unread-counts";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Patch rich blocks on a message already sent.
   *
   * <p>{@code PATCH /v1/conversations/{id}/messages/{messageId}/blocks}
   *
   * <p>Blocks are addressable by {@code block.id} after the fact, so an agent can stream option
   * cards and then fill in the reasons for them, or stop a {@code status} block spinning. Both edit
   * something already on the visitor's screen.
   *
   * <p>Each patch is {@code {"id": …, "set": {…}}} and the object is <strong>strict</strong>: no
   * other key, and {@code type} in particular is refused — you cannot turn one kind of block into
   * another. Between 1 and 50 patches per call; the batch is one agent turn's worth of revisions,
   * not a bulk rewrite.
   *
   * <p><strong>All or nothing.</strong> An unknown block id is a 404 rather than a success against
   * nothing, and a merged result that is not a valid block refuses the <em>whole</em> batch — a
   * half-applied patch would leave a shape no author ever wrote.
   *
   * <p>{@code messages:write}, <strong>plus</strong> {@code conversations:read}: the handler
   * re-checks that the thread is in your scope, and a key with neither
   * {@code conversations:read} nor {@code conversations:read:assigned} is scoped to nobody's
   * conversations, so every id 404s. That second requirement is not declared on the route and the
   * 404 does not mention it.
   */
  public Map<String, Object> updateBlocks(
      String conversationId, String messageId, List<Map<String, Object>> patches) {
    String path =
        COLLECTION
            + "/"
            + seg(conversationId, "conversationId")
            + "/messages/"
            + seg(messageId, "messageId")
            + "/blocks";
    required(patches, "patches");
    if (patches.isEmpty() || patches.size() > 50) {
      throw new IllegalArgumentException(
          "updateBlocks: the API accepts 1 to 50 patches per call, got " + patches.size());
    }
    return object(transport.patch(path, Query.of(), Map.of("patches", patches)), path);
  }

  /**
   * Hand an open conversation back to an assistant after a human took it.
   *
   * <p>{@code POST /v1/conversations/{id}/return-to-bot}
   *
   * <p>{@code conversations:assign}, a higher bar than replying, because this moves a thread to
   * somebody else — unlike {@link #setAgentHandling}, where a BYO agent can only ever claim or
   * release <em>itself</em>.
   *
   * <p>{@code botId} is required and is deliberately <strong>not</strong> defaulted to whoever
   * owned the thread before. The previous owner is recoverable, but a thread three assistants have
   * touched has no obvious "the" bot, and guessing wrong means the visitor talks to the returns
   * agent about a size exchange. {@code chatly.bots().list()} lists the choices.
   *
   * <p>Refused on a pending, snoozed, closed or merged thread, and on a bot that is not active.
   * When it succeeds the conversation has left the human queue.
   *
   * <p>From an API key the internal note is attributed to {@code "api"} rather than to a person —
   * the route reads the signed-in user and a key has none, which is correct here: inventing an
   * author would put a machine id in a field the inbox renders as "who did this".
   */
  public Map<String, Object> returnToBot(String conversationId, String botId) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/return-to-bot";
    return object(
        transport.post(path, Query.of(), Map.of("botId", required(botId, "botId"))), path);
  }

  /**
   * What is already on record for this conversation. {@code GET /v1/conversations/{id}/outcomes}
   *
   * <p>One object, newest first, merged from three tables — AI resolutions, escalations, and
   * {@code conversation.purchased} events. Each row's {@code source} is the point: it tells your
   * own {@code agent_report} apart from the visitor's chip and from an escalation a human asked
   * for, which is what makes a number in the funnel auditable.
   *
   * <p>Each arm is capped at 20, so a pathological thread is bounded rather than slow. No cursor.
   *
   * <p>Needs {@code conversations:read} or {@code conversations:read:assigned} — and a key with
   * <em>neither</em> is scoped to nobody's threads, which means a 404 on every id rather than a
   * 403 saying so.
   */
  public Map<String, Object> outcomes(String conversationId) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/outcomes";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Report what happened, so the AI funnels count it.
   *
   * <p>{@code POST /v1/conversations/{id}/outcomes}
   *
   * <p>{@code outcome} is required and is one of {@code resolved}, {@code escalated},
   * {@code purchased}. The body is strict.
   *
   * <p>Per outcome, the thing that catches people:
   *
   * <ul>
   *   <li>{@code escalated} — {@code reason} is required in practice. It is classified into the
   *       column the handoff-reason funnel groups on and posted to the receiving human as an
   *       internal note; an escalation with no reason is a thread somebody picks up knowing nothing
   *       about what was already tried.
   *   <li>{@code purchased} — {@code outcomeRef} (your order or payment id, unique per workspace
   *       forever) is what stands between a retried POST and a double-counted purchase.
   *       {@code resolved} and {@code escalated} are soft-idempotent on their own terms; a second
   *       {@code purchased} would be a second amount.
   *   <li>{@code valueCents} and {@code currency} are <strong>refused</strong> on anything but
   *       {@code purchased}, rather than ignored — a 201 that silently dropped the number would
   *       leave you believing it was recorded.
   * </ul>
   *
   * <p>And one thing the response cannot tell you: <strong>nothing in Chatly sums
   * {@code valueCents}.</strong> There is no revenue report. It lands on the CDP event, where the
   * contact timeline shows it and a goal can convert on it, and that is the whole of what it
   * reaches.
   *
   * <p>{@code created: false} with a 201 means an equal outcome was already on record and this call
   * changed nothing — a replayed resolution inside 24h, an escalation inside 60s, or a purchase
   * whose {@code outcomeRef} we already hold. Read the flag; the status is 201 either way.
   *
   * <p>{@code conversations:write}, <strong>plus</strong> a read scope: without
   * {@code conversations:read} (or {@code :read:assigned}) the handler's visibility check sees
   * nothing and every conversation id is a 404.
   */
  public Map<String, Object> recordOutcome(String conversationId, Map<String, Object> outcome) {
    String path = COLLECTION + "/" + seg(conversationId, "conversationId") + "/outcomes";
    required(outcome, "outcome");
    if (!outcome.containsKey("outcome")) {
      throw new IllegalArgumentException(
          "recordOutcome: the map must contain an `outcome` key — one of resolved, escalated,"
              + " purchased");
    }
    return object(transport.post(path, Query.of(), outcome), path);
  }

  /** {@link #recordOutcome(String, Map)} for the no-detail case. */
  public Map<String, Object> recordOutcome(String conversationId, String outcome) {
    return recordOutcome(conversationId, Map.of("outcome", required(outcome, "outcome")));
  }
}
