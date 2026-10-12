package com.chatlychat.chatly;

import java.util.Map;

/**
 * Full-text search across contacts, conversations, messages, tickets and the knowledge base.
 *
 * <p>Reached as {@code chatly.search()}.
 */
public final class SearchResource extends Resource {

  private static final String PATH = "/v1/search";

  /** The server's bound on the search term. Checked locally so an over-long term fails here. */
  private static final int MAX_TERM_LENGTH = 256;

  SearchResource(Transport transport) {
    super(transport);
  }

  /**
   * Search everything. {@code GET /v1/search?q=…}
   *
   * <p>The response is a page envelope with two extra keys, {@code facets} and {@code tier}, both
   * reachable through {@link Page#raw()}. {@code tier} tells you which search backend answered
   * (Postgres full-text versus the vector index), which is the first thing to look at when results
   * are not what you expected.
   *
   * @param term 1 to 256 characters. Both bounds are the server's and are checked here, because a
   *     257-character term is a 400 that costs a round trip to discover.
   */
  public Page<Map<String, Object>> query(String term) {
    return query(term, Query.of());
  }

  /**
   * Search everything, with extra query parameters.
   *
   * <p>Narrow by kind with repeated {@code kinds[]} parameters:
   *
   * <pre>{@code
   * chatly.search().query("refund", Query.of().add("kinds[]", "kb").add("kinds[]", "article"));
   * }</pre>
   *
   * Note those are two parameters, not one comma-joined value — see {@link Query}.
   */
  public Page<Map<String, Object>> query(String term, Query query) {
    return page(PATH, q(query).copy().set("q", checkTerm(term)));
  }

  /** Search, pages walked for you. */
  public Pager<Map<String, Object>> queryAuto(String term, Query query) {
    return pager(PATH, q(query).copy().set("q", checkTerm(term)));
  }

  /**
   * Help-centre articles only — {@code /v1/search} narrowed to {@code kinds[]=kb}.
   *
   * <p>Its own method because "find the article that answers this" is the common case, and getting
   * there otherwise means knowing that the {@code kinds[]} parameter exists at all.
   */
  public Page<Map<String, Object>> kb(String term) {
    return query(term, Query.of().add("kinds[]", "kb"));
  }

  private static String checkTerm(String term) {
    required(term, "term");
    if (term.length() > MAX_TERM_LENGTH) {
      throw new IllegalArgumentException(
          "search: the API accepts a term of at most "
              + MAX_TERM_LENGTH
              + " characters, got "
              + term.length());
    }
    return term;
  }
}
