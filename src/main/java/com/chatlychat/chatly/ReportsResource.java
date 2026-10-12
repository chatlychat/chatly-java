package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Reporting aggregates.
 *
 * <p>Reached as {@code chatly.reports()}. Every method here takes an optional {@link Query} for the
 * date range and filters the dashboard exposes; none is paginated.
 */
public final class ReportsResource extends Resource {

  ReportsResource(Transport transport) {
    super(transport);
  }

  /** Headline numbers. {@code GET /v1/reports/overview} — an object. */
  public Map<String, Object> overview() {
    return overview(Query.of());
  }

  /** Headline numbers for a date range. */
  public Map<String, Object> overview(Query query) {
    String path = "/v1/reports/overview";
    return object(transport.get(path, q(query)), path);
  }

  /**
   * Per-agent performance. {@code GET /v1/reports/agents} — a bare <em>array</em>, one row per
   * agent.
   *
   * <p>Not to be confused with {@code chatly.workspace().agents()}, which lists the people; this
   * lists their numbers.
   */
  public List<Map<String, Object>> agents() {
    return agents(Query.of());
  }

  /** Per-agent performance for a date range. */
  public List<Map<String, Object>> agents(Query query) {
    String path = "/v1/reports/agents";
    return array(transport.get(path, q(query)), path);
  }

  /**
   * A daily time series. {@code GET /v1/reports/conversations-by-day}
   *
   * <p>A bare array of {@code {day, count}} objects, oldest first.
   */
  public List<Map<String, Object>> conversationsByDay() {
    return conversationsByDay(Query.of());
  }

  /** A daily time series over a date range. */
  public List<Map<String, Object>> conversationsByDay(Query query) {
    String path = "/v1/reports/conversations-by-day";
    return array(transport.get(path, q(query)), path);
  }

  /** SLA attainment. {@code GET /v1/reports/sla} — an object. */
  public Map<String, Object> sla() {
    return sla(Query.of());
  }

  /** SLA attainment for a date range. */
  public Map<String, Object> sla(Query query) {
    String path = "/v1/reports/sla";
    return object(transport.get(path, q(query)), path);
  }
}
