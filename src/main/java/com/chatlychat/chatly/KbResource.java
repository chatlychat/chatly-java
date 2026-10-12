package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * The knowledge base behind your help centre.
 *
 * <p>Reached as {@code chatly.kb()}.
 */
public final class KbResource extends Resource {

  KbResource(Transport transport) {
    super(transport);
  }

  /** One page of articles. {@code GET /v1/kb/articles} */
  public Page<Map<String, Object>> articles() {
    return articles(Query.of());
  }

  /** One page of articles, with query parameters. */
  public Page<Map<String, Object>> articles(Query query) {
    return page("/v1/kb/articles", query);
  }

  /** Every article, pages walked for you. */
  public Pager<Map<String, Object>> articlesAuto() {
    return articlesAuto(Query.of());
  }

  /** Every article matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> articlesAuto(Query query) {
    return pager("/v1/kb/articles", query);
  }

  /**
   * One article by slug. {@code GET /v1/kb/articles/{slug}}
   *
   * <p>By slug, not id — the slug is what appears in the help-centre URL. It is percent-encoded
   * here, so a slug with a non-ASCII character resolves.
   */
  public Map<String, Object> article(String slug) {
    String path = "/v1/kb/articles/" + seg(slug, "slug");
    return object(transport.get(path, Query.of()), path);
  }

  /** The article collections. {@code GET /v1/kb/collections} — a bare array. */
  public List<Map<String, Object>> collections() {
    String path = "/v1/kb/collections";
    return array(transport.get(path, Query.of()), path);
  }
}
