package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Knowledge gaps — questions customers asked that the knowledge base could not answer.
 *
 * <p>Reached as {@code chatly.knowledgeGaps()}.
 *
 * <p>Recorded by the AI layer when a retrieval finds nothing useful, clustered by question, and
 * ordered most-asked first. This is the backlog of articles worth writing, derived from what people
 * actually asked rather than from what someone guessed they would.
 */
public final class KnowledgeGapsResource extends Resource {

  private static final String COLLECTION = "/v1/knowledge-gaps";

  KnowledgeGapsResource(Transport transport) {
    super(transport);
  }

  /**
   * Open gaps, most-asked first. {@code GET /v1/knowledge-gaps} — a bare array.
   *
   * <p>Not paginated, and it is bounded at the <em>recorder</em>: a workspace holds at most 200 open
   * gaps, after which new questions are folded into existing clusters rather than appended. So this
   * list is complete by construction, and a workspace that seems stuck at 200 is at the cap.
   */
  public List<Map<String, Object>> list() {
    return list(Query.of());
  }

  /**
   * Gaps, filtered. {@code GET /v1/knowledge-gaps?status=…&limit=…}
   *
   * <p>{@code status} is one of {@code open}, {@code drafted}, {@code resolved},
   * {@code dismissed}. <strong>An unrecognised value is ignored rather than refused</strong> — the
   * handler parses it with {@code safeParse} and omits the filter when it fails — so a typo returns
   * every status with a 200 and nothing says so. {@code limit} is clamped to 1-200, defaulting to
   * 100.
   */
  public List<Map<String, Object>> list(Query query) {
    return array(transport.get(COLLECTION, q(query)), COLLECTION);
  }

  /**
   * Triage a gap. {@code PATCH /v1/knowledge-gaps/{id}}
   *
   * @param status one of {@code open}, {@code drafted}, {@code resolved}, {@code dismissed}. The
   *     body is strict here, unlike the list filter above: an unknown value is a 400.
   */
  public Map<String, Object> update(String gapId, String status) {
    String path = COLLECTION + "/" + seg(gapId, "gapId");
    return object(
        transport.patch(path, Query.of(), Map.of("status", required(status, "status"))), path);
  }

  /**
   * Draft an article from the conversation this gap was found in.
   *
   * <p>{@code POST /v1/knowledge-gaps/{id}/draft}
   *
   * <p>One LLM call, and what it produces is a <strong>draft</strong> article — unpublished, not
   * visible in any help centre until a human publishes it. No request body: the source material is
   * the conversation already on the gap.
   *
   * <p>Two outcomes, and both are 201. The response carries either the new article's id or a reason
   * there is none, so read it rather than assuming. A gap with no conversation to draft from is a
   * 400 instead — that one is an error, not an outcome.
   */
  public Map<String, Object> draft(String gapId) {
    String path = COLLECTION + "/" + seg(gapId, "gapId") + "/draft";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }
}
