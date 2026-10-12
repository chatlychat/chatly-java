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
}
