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

  // ------------------------------------------------------------------------
  // The nine extended reports.
  //
  // Two things are true of all of them and of none of the four above, and
  // both are worth reading once rather than nine times.
  //
  // 1. THE WINDOW IS CLAMPED, NOT VALIDATED. Every `?days` / `?weeks` here is
  //    parsed with `Math.min(max, Math.max(min, parseInt(raw) || default))`,
  //    so `?days=9999` answers a 200 over the maximum window and `?days=soon`
  //    answers a 200 over the DEFAULT one. There is no error to catch, and the
  //    period you asked for is not necessarily the period you got. Only
  //    `chatly.bots().analytics()` refuses a non-numeric window, which makes
  //    it the odd one out rather than the rule.
  // 2. NONE OF THEM PAGES. Each is an aggregate with a cap and a remainder
  //    bucket, so a caller cannot reach past the cap — there is no cursor to
  //    follow and no `listAuto`. Where a cap exists it is named in the method.
  // ------------------------------------------------------------------------

  /**
   * Volume and resolution grouped by captured campaign data.
   *
   * <p>{@code GET /v1/reports/attribution} — a <strong>bare array</strong>, not a page envelope.
   *
   * <p>Two rows are easy to get wrong. The row with {@code value: null} is the <em>untagged</em>
   * bucket and is a real row: dropping it as "missing data" inflates every other row's
   * {@code percentage}. And the tail beyond the row cap is summed into a single row, so there is
   * genuinely nothing to page to.
   *
   * @param query {@code dimension} selects what to group by; {@code days} is 1-90, default 30.
   */
  public List<Map<String, Object>> attribution(Query query) {
    String path = "/v1/reports/attribution";
    return array(transport.get(path, q(query)), path);
  }

  /** Attribution over the default 30-day window. */
  public List<Map<String, Object>> attribution() {
    return attribution(Query.of());
  }

  /**
   * Weekly contact-retention cohort grid. {@code GET /v1/reports/cohorts}
   *
   * <p>One object, <strong>not a bare array of cells</strong>. {@code weeks} comes back on the
   * response and is the window the report <em>actually used</em>, which can differ from the
   * {@code ?weeks=} you asked for: a caller labelling its axes needs the echoed value rather than
   * its own input.
   *
   * @param query {@code weeks} is 2-26, default 8
   */
  public Map<String, Object> cohorts(Query query) {
    String path = "/v1/reports/cohorts";
    return object(transport.get(path, q(query)), path);
  }

  /** Cohorts over the default 8-week window. */
  public Map<String, Object> cohorts() {
    return cohorts(Query.of());
  }

  /**
   * Volume, resolution time and CSAT by label. {@code GET /v1/reports/labels}
   *
   * <p>One object. {@code rows} is capped at the <strong>50 busiest labels</strong> with everything
   * else counted in {@code otherLabels}, and {@code trend} is emitted for the top 8 only — there is
   * no cursor, so the tail is unreachable by design rather than by omission. {@code trend[].counts}
   * is keyed by label <em>name</em>, not id.
   *
   * @param query {@code from} / {@code to} as an ISO date or instant ({@code from} inclusive,
   *     {@code to} exclusive), and {@code business_hours=true|false}
   */
  public Map<String, Object> labels(Query query) {
    String path = "/v1/reports/labels";
    return object(transport.get(path, q(query)), path);
  }

  /** The label report over the server's default range. */
  public Map<String, Object> labels() {
    return labels(Query.of());
  }

  /**
   * First-response and resolution percentiles. {@code GET /v1/reports/response-times}
   *
   * <p>One object. Percentiles are <strong>seconds</strong> and are {@code null} — not 0 — when
   * {@code sampleSize} is 0, so a null is "nothing to measure" rather than "instant".
   *
   * <p>It carries a {@code byDay} series of the same shape {@link #conversationsByDay()} returns
   * bare. The two routes are not interchangeable: one is an object wrapping the series, the other
   * is the series.
   *
   * @param query {@code days} is 1-90, default 30
   */
  public Map<String, Object> responseTimes(Query query) {
    String path = "/v1/reports/response-times";
    return object(transport.get(path, q(query)), path);
  }

  /** Response times over the default 30-day window. */
  public Map<String, Object> responseTimes() {
    return responseTimes(Query.of());
  }

  /**
   * Volume grouped by channel of origin. {@code GET /v1/reports/sources}
   *
   * <p>A <strong>bare array</strong>. {@code channelId} is null when a conversation carries a
   * channel type with no surviving channel row — a deleted channel, historically — and in that case
   * {@code channelName} is a human label for the <em>type</em> rather than null. So a null id does
   * not mean a null name, and neither means the row is junk.
   *
   * @param query {@code days} is 1-90, default <strong>7</strong> — not 30 like its neighbours
   */
  public List<Map<String, Object>> sources(Query query) {
    String path = "/v1/reports/sources";
    return array(transport.get(path, q(query)), path);
  }

  /** Sources over the default 7-day window. */
  public List<Map<String, Object>> sources() {
    return sources(Query.of());
  }

  /**
   * Per-team volume, percentiles, CSAT and SLA breaches. {@code GET /v1/reports/teams}
   *
   * <p>One object whose {@code rows} hold one entry per team, <strong>plus a row with a null
   * {@code teamId} for the unassigned bucket</strong> — which is a real row, not a parsing
   * accident.
   *
   * <p>{@code businessHours.applied: false} means the durations are wall-clock: the workspace has
   * no business hours configured, or you did not ask for them. Reading a duration without checking
   * that flag compares two different measurements.
   *
   * @param query {@code from} / {@code to}, and {@code business_hours=true|false}
   */
  public Map<String, Object> teams(Query query) {
    String path = "/v1/reports/teams";
    return object(transport.get(path, q(query)), path);
  }

  /** The team report over the server's default range. */
  public Map<String, Object> teams() {
    return teams(Query.of());
  }

  /**
   * The latest theme batch: what customers wrote about, clustered. {@code GET /v1/reports/themes}
   *
   * <p>One object, <strong>not a bare array</strong>. {@code batchDate} names the snapshot every row
   * was measured over, and is {@code null} with an empty {@code themes} when no batch has ever been
   * generated — so empty means "the clusterer has not run", not "customers said nothing".
   *
   * <p>Theme rows omit {@code conversationIds}; {@link #themeConversations} serves those.
   *
   * @param date optional {@code YYYY-MM-DD}, selecting an older batch. It reaches a {@code date}
   *     <em>column</em>, so it must be a calendar date: {@code ?date=yesterday} used to arrive at
   *     Postgres untouched and come back as a 500 about invalid input syntax. The handler validates
   *     it now — including rolled-over values like {@code 2026-02-30}, which match the shape and
   *     are not dates — and this method sends it only as given, so pass a real date.
   */
  public Map<String, Object> themes(String date) {
    String path = "/v1/reports/themes";
    return object(transport.get(path, Query.of().add("date", date)), path);
  }

  /** The most recent theme batch. */
  public Map<String, Object> themes() {
    return themes(null);
  }

  /**
   * The conversations in one theme, as the snapshot recorded them.
   *
   * <p>{@code GET /v1/reports/themes/{id}/conversations}
   *
   * <p>One object wrapping the id list. <strong>An unknown or purged theme id answers 200 with an
   * empty list rather than a 404</strong>, so this route cannot tell you apart from a theme that
   * genuinely holds no conversations — do not use an empty result as evidence the id is valid.
   */
  public Map<String, Object> themeConversations(String themeId) {
    String path = "/v1/reports/themes/" + seg(themeId, "themeId") + "/conversations";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Voice analytics — volume, answer rate, talk time, heatmap, per-agent.
   *
   * <p>{@code GET /v1/reports/voice}
   *
   * <p>One object. Rates are <strong>0-1, not percentages</strong>.
   *
   * <p>{@code recordingStorage.exact: false} means {@code totalBytesUpperBound} is part measured
   * and part bitrate extrapolation over {@code unmeasuredCalls} — so that flag is as much part of
   * the contract as the byte count, and billing anyone on the number without reading it is billing
   * them on an estimate.
   *
   * @param query {@code days} is 1-365, default 30
   */
  public Map<String, Object> voice(Query query) {
    String path = "/v1/reports/voice";
    return object(transport.get(path, q(query)), path);
  }

  /** Voice analytics over the default 30-day window. */
  public Map<String, Object> voice() {
    return voice(Query.of());
  }
}
