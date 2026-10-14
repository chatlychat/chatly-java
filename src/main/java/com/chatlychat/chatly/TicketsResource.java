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

  // ------------------------------------------------------------------------
  // The five read routes below are all gated on `conversations:read`, not on a
  // tickets permission — there is no `tickets:read` scope in this product. A
  // key scoped for tickets and not for conversations gets a 403 from every one
  // of them, which is confusing until you know. `GET /v1/tickets` itself is
  // the same.
  // ------------------------------------------------------------------------

  /**
   * Every conversation this ticket covers, origin first.
   *
   * <p>{@code GET /v1/tickets/{id}/conversations} — a bare array.
   *
   * <p>A tracker ticket explains one outage that fifty customers wrote in about, and this is how
   * you see all fifty from the ticket. {@code isOrigin} marks the thread the ticket was opened
   * from.
   *
   * <p>Not paginated: the set is bounded, and the ceiling is applied in the query rather than at
   * the ticket — a tracker legitimately spans the workspace while the caller may be off some of the
   * inboxes it reaches into, so what you get back is the intersection.
   */
  public List<Map<String, Object>> conversations(String ticketId) {
    String path = COLLECTION + "/" + seg(ticketId, "ticketId") + "/conversations";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * How many open tickets a conversation carries.
   *
   * <p>{@code GET /v1/tickets/open-count/{conversationId}} — {@code {"openTickets": n}}.
   *
   * <p>Note the shape of the path: the conversation id is the <em>last</em> segment under a literal
   * {@code open-count}, so this is not a sub-resource of a ticket at all. Keyed by a conversation
   * you supply, it needs the same door as opening that conversation — answering it for a thread you
   * are kept off would both leak the fact that there is unfinished work on it and confirm the id
   * exists.
   */
  public Map<String, Object> openCount(String conversationId) {
    String path = COLLECTION + "/open-count/" + seg(conversationId, "conversationId");
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Time logged against a ticket, with totals. {@code GET /v1/tickets/{id}/time}
   *
   * <p><strong>An object with an {@code items} array and no cursor — not a page.</strong> Beside
   * the entries sit {@code totalMinutes} and {@code billableMinutes}; the second is computed here
   * rather than left for the caller to derive, because it is the figure that reaches an invoice and
   * two implementations of it would eventually disagree.
   *
   * <p>Reading time is on this SDK's surface; <em>logging</em> it is not — that route records which
   * agent spent the time, and a key is not a person.
   */
  public Map<String, Object> time(String ticketId) {
    String path = COLLECTION + "/" + seg(ticketId, "ticketId") + "/time";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * A ticket's status transitions, with time spent in each status.
   *
   * <p>{@code GET /v1/tickets/{id}/status-history}
   *
   * <p>One object, oldest first. {@code truncated} is the field that matters: the service caps the
   * timeline at 500 transitions, and that flag is what distinguishes a capped history from a short
   * one. Computing a cycle time from a truncated timeline measures the wrong interval.
   */
  public Map<String, Object> statusHistory(String ticketId) {
    String path = COLLECTION + "/" + seg(ticketId, "ticketId") + "/status-history";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Tickets this one was split from or follows up, and vice versa.
   *
   * <p>{@code GET /v1/tickets/{id}/lineage}
   *
   * <p>One object holding several relation lists, each capped at 25 by the service. Each entry is
   * enough of a ticket to render a recognisable link — id, number, title, status — and not the
   * whole row, so a lineage of 25 is not 25 full tickets.
   */
  public Map<String, Object> lineage(String ticketId) {
    String path = COLLECTION + "/" + seg(ticketId, "ticketId") + "/lineage";
    return object(transport.get(path, Query.of()), path);
  }
}
