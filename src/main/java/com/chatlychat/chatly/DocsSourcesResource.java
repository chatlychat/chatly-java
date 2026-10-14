package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Documentation sources — external docs sites synced into the knowledge base.
 *
 * <p>Reached as {@code chatly.docsSources()}.
 *
 * <p>Five providers: GitBook, Forumulate, Discourse, ReadMe, and a sitemap crawler
 * ({@code website}). A source is one <em>space</em> inside one provider, optionally narrowed to a
 * path prefix, synced on a schedule and turned into KB articles the AI can cite. The connect flow
 * is {@link #providers()} to see what is available, {@link #discover} to list what a token can
 * reach, then {@link #create} with a chosen space.
 *
 * <p>Everything here is {@code kb:read} / {@code kb:write}. No response field ever carries the
 * provider token back.
 */
public final class DocsSourcesResource extends Resource {

  private static final String COLLECTION = "/v1/docs-sources";

  DocsSourcesResource(Transport transport) {
    super(transport);
  }

  /**
   * Providers that can be connected. {@code GET /v1/docs-sources/providers} — a bare array.
   *
   * <p>A static registry, and worth reading rather than hardcoding: each row carries
   * {@code needsBaseUrl} and {@code needsToken}, which is how a connect form knows whether to ask
   * for a self-hosted instance address and whether a credential is required at all. <strong>Two
   * providers accept an empty token</strong> — a public Discourse category and the sitemap crawler
   * read only what any visitor can — so demanding one is an obstacle you would be inventing.
   */
  public List<Map<String, Object>> providers() {
    String path = COLLECTION + "/providers";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * Connected sources. {@code GET /v1/docs-sources} — a bare array.
   *
   * <p>Not paginated; a workspace connects a handful. Each row carries its sync status and counts,
   * which is where to look when the bot is citing stale pages.
   */
  public List<Map<String, Object>> list() {
    return array(transport.get(COLLECTION, Query.of()), COLLECTION);
  }

  /**
   * Spaces this token can reach. {@code POST /v1/docs-sources/discover}
   *
   * <p>A POST that answers a <strong>bare array</strong> — one of exactly two on this surface — and
   * with a 201, because Nest answers a POST that way and there is no {@code @HttpCode} here;
   * nothing is created. It is a POST only because the token belongs in a body rather than a query
   * string.
   *
   * <p>Required: {@code provider} and {@code token} (which may be empty for the two providers that
   * need none). {@code baseUrl} for a self-hosted instance.
   *
   * <p>{@code organizationId} is the one worth knowing about. An <em>org-scoped</em> token cannot
   * read the provider's organisation list, so discovery without it fails the whole connect flow
   * with an error naming a path the operator never asked about. An account-wide token without it
   * gets every space in every organisation — the right answer for someone who has not chosen one
   * yet, and the wrong one for everybody else: sixty spaces across unrelated organisations.
   */
  public List<Map<String, Object>> discover(Map<String, Object> request) {
    String path = COLLECTION + "/discover";
    return array(transport.post(path, Query.of(), required(request, "request")), path);
  }

  /**
   * Connect a source. {@code POST /v1/docs-sources}
   *
   * <p>Required: {@code provider}, {@code token}, {@code organizationId} and {@code spaceId} — the
   * last two from {@link #discover}. Optional: {@code baseUrl}, {@code label},
   * {@code publishOnSync}, {@code syncIntervalMinutes} and a path prefix.
   *
   * <p>{@code syncIntervalMinutes} has a floor of 15 because that matches the scan cadence: a
   * shorter interval cannot be honoured and would only look like it was.
   *
   * <p>The first sync is enqueued <em>before</em> this returns, so the source comes back with
   * {@code syncStatus: "queued"} and no article counts yet. A caller that reads the counts
   * immediately reads zeros.
   */
  public Map<String, Object> create(Map<String, Object> source) {
    return object(transport.post(COLLECTION, Query.of(), required(source, "source")), COLLECTION);
  }

  /**
   * Rename, pause, re-schedule, or rotate the token of a source.
   *
   * <p>{@code PATCH /v1/docs-sources/{id}}
   *
   * <p>Partial. A replacement {@code token} is <strong>verified against the provider before it is
   * stored</strong>, and specifically against <em>this source's space</em> — so a token that
   * authenticates but cannot see the space is refused and the working credential is left alone
   * rather than replaced with a broken one. No response field carries the token back.
   */
  public Map<String, Object> update(String sourceId, Map<String, Object> changes) {
    String path = COLLECTION + "/" + seg(sourceId, "sourceId");
    return object(transport.patch(path, Query.of(), required(changes, "changes")), path);
  }

  /** Sync now, as a delta. {@code POST /v1/docs-sources/{id}/sync} */
  public Map<String, Object> sync(String sourceId) {
    return sync(sourceId, false);
  }

  /**
   * Sync now — enqueues the same job the scheduler runs.
   *
   * <p>{@code POST /v1/docs-sources/{id}/sync}
   *
   * @param full {@code true} resets the checkpoint and re-reads the whole space. <strong>This is
   *     the parameter to reach for when the READER changed rather than the content.</strong> A
   *     source that holds a checkpoint answers an ordinary "sync now" with a delta, so after a
   *     provider-side fix the run correctly finds nothing new and the knowledge base stays exactly
   *     as wrong as it was — which is a thing that happened here, to a source that had indexed 6 of
   *     100 pages.
   */
  public Map<String, Object> sync(String sourceId, boolean full) {
    String path = COLLECTION + "/" + seg(sourceId, "sourceId") + "/sync";
    return object(transport.post(path, Query.of(), Map.of("full", full)), path);
  }

  /**
   * Subscribe a source to its provider's change events.
   *
   * <p>{@code POST /v1/docs-sources/{id}/webhook}
   *
   * <p>Push closes the gap between "docs updated" and "the bot knows" from one sync interval — six
   * hours by default — to a round trip. No request body.
   *
   * <p><strong>A refusal here is a success, not an error.</strong> It answers 201 with
   * {@code subscribed: false} and a reason when the stored token cannot manage webhooks, which is
   * the common case: read-only tokens are what people paste. The source keeps polling either way,
   * and the reason names the scope to add. So branch on {@code subscribed}, not on the status code.
   */
  public Map<String, Object> webhook(String sourceId) {
    String path = COLLECTION + "/" + seg(sourceId, "sourceId") + "/webhook";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }

  /**
   * Disconnect a source, keeping the articles it created.
   *
   * <p>{@code DELETE /v1/docs-sources/{id}}
   *
   * <p>Answers 200 with a body, not a 204: {@code kept} is how many articles stayed behind.
   * <strong>None are deleted</strong> — they simply stop being refreshed, which means a
   * disconnected source leaves a knowledge base that looks current and is frozen. Delete the
   * articles yourself if that is not what you want.
   */
  public Map<String, Object> delete(String sourceId) {
    String path = COLLECTION + "/" + seg(sourceId, "sourceId");
    return object(transport.delete(path, Query.of(), null), path);
  }
}
