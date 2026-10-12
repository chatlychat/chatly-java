package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Tickets — the tracked, assignable work items behind conversations.
 *
 * <p>Reached as {@code chatly.tickets()}.
 */
public final class TicketsResource extends Resource {

  private static final String COLLECTION = "/v1/tickets";

  TicketsResource(Transport transport) {
    super(transport);
  }

  /** One page of tickets. {@code GET /v1/tickets} */
  public Page<Map<String, Object>> list() {
    return list(Query.of());
  }

  /** One page of tickets, with query parameters. */
  public Page<Map<String, Object>> list(Query query) {
    return page(COLLECTION, query);
  }

  /** Every ticket, pages walked for you. */
  public Pager<Map<String, Object>> listAuto() {
    return listAuto(Query.of());
  }

  /** Every ticket matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> listAuto(Query query) {
    return pager(COLLECTION, query);
  }

  /**
   * A ticket's comments. {@code GET /v1/tickets/{id}/comments}
   *
   * <p>A bare array, not a page.
   */
  public List<Map<String, Object>> comments(String ticketId) {
    String path = COLLECTION + "/" + seg(ticketId, "ticketId") + "/comments";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * The tickets attached to one conversation.
   *
   * <p>{@code GET /v1/tickets/for-conversation/{conversationId}}
   *
   * <p>An array, because a conversation can carry more than one ticket; an empty array means none,
   * which is not an error.
   */
  public List<Map<String, Object>> forConversation(String conversationId) {
    String path = COLLECTION + "/for-conversation/" + seg(conversationId, "conversationId");
    return array(transport.get(path, Query.of()), path);
  }

  /** The workspace's ticket queues. {@code GET /v1/tickets/queues} — a bare array. */
  public List<Map<String, Object>> queues() {
    String path = COLLECTION + "/queues";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * Merge one ticket into another. {@code POST /v1/tickets/{id}/merge}
   *
   * @param ticketId the ticket that survives
   * @param loserId the ticket that is absorbed
   */
  public Map<String, Object> merge(String ticketId, String loserId) {
    String path = COLLECTION + "/" + seg(ticketId, "ticketId") + "/merge";
    return object(
        transport.post(path, Query.of(), Map.of("loserId", required(loserId, "loserId"))), path);
  }

  /**
   * Split a ticket in two. {@code POST /v1/tickets/{id}/split}
   *
   * @param title the title of the new ticket
   */
  public Map<String, Object> split(String ticketId, String title) {
    return split(ticketId, Map.of("title", required(title, "title")));
  }

  /** As {@link #split(String, String)}, with the full payload. */
  public Map<String, Object> split(String ticketId, Map<String, Object> payload) {
    String path = COLLECTION + "/" + seg(ticketId, "ticketId") + "/split";
    return object(transport.post(path, Query.of(), required(payload, "payload")), path);
  }

  /**
   * Apply one action to many tickets. {@code POST /v1/tickets/bulk}
   *
   * @param ids the tickets to act on
   * @param action what to do, e.g. {@code "close"}
   */
  public Map<String, Object> bulk(List<String> ids, String action) {
    return bulk(Map.of("ids", required(ids, "ids"), "action", required(action, "action")));
  }

  /**
   * As {@link #bulk(List, String)}, with the full payload — for an action that takes extra fields
   * (an assignee, a queue).
   */
  public Map<String, Object> bulk(Map<String, Object> payload) {
    String path = COLLECTION + "/bulk";
    return object(transport.post(path, Query.of(), required(payload, "payload")), path);
  }
}
