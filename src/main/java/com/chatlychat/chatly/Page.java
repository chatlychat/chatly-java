package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One page of a cursor-paginated list.
 *
 * <p><strong>Never sort or page on {@code id}.</strong> Identifiers in this product are a mix of
 * UUID v4 and v7, so {@code id} order is not time order — a v4 id sorts into a random position
 * among v7 ones. The cursor is the only correct sequence, which is why this class exposes one and no
 * "page number".
 *
 * <p><strong>Three page envelopes exist on this API, not one.</strong> Most routes return
 * {@code {items, nextCursor}}; tickets, KB articles, CDP events and webhook deliveries add
 * {@code hasMore}; {@code /v1/search} adds {@code facets} and {@code tier}, and
 * {@code /v1/conversations/unattended} adds {@code total}. Rather than pretend otherwise, this type
 * guarantees only {@link #items()} and {@link #nextCursor()}, offers {@link #hasMore()} and
 * {@link #total()} as {@link Optional} (empty where the route does not send them — <em>not</em>
 * {@code false} or {@code 0}, which would be a lie about a field that was never there), and keeps
 * the whole decoded envelope on {@link #raw()} so nothing is unreachable.
 *
 * @param <T> element type; {@code Map<String,Object>} throughout this SDK
 */
public final class Page<T> {

  private final List<T> items;
  private final String nextCursor;
  private final Map<String, Object> raw;

  Page(List<T> items, String nextCursor, Map<String, Object> raw) {
    this.items = items == null ? List.of() : List.copyOf(items);
    this.nextCursor = nextCursor;
    this.raw = raw == null ? Map.of() : raw;
  }

  /** The rows on this page, in the order the server returned them. Never null. */
  public List<T> items() {
    return items;
  }

  /**
   * The cursor to pass as {@code ?cursor=} to fetch the next page.
   *
   * <p>Empty means this is the last page. An empty <em>string</em> from the server is normalised to
   * empty here, because {@code ?cursor=} with no value is not a request for the next page and
   * walking on it would re-fetch page one forever.
   */
  public Optional<String> nextCursor() {
    return Optional.ofNullable(nextCursor);
  }

  /** Whether {@link #nextCursor()} is present. */
  public boolean hasNextPage() {
    return nextCursor != null;
  }

  /**
   * The server's own {@code hasMore} flag, where the route sends one.
   *
   * <p>Empty when the route does not send it, which is about half of them. Prefer
   * {@link #hasNextPage()}, which is derived from the cursor and therefore defined everywhere.
   */
  public Optional<Boolean> hasMore() {
    Object v = raw.get("hasMore");
    return v instanceof Boolean b ? Optional.of(b) : Optional.empty();
  }

  /** The server's {@code total} count, sent only by {@code /v1/conversations/unattended}. */
  public Optional<Long> total() {
    Object v = raw.get("total");
    if (v instanceof Long l) {
      return Optional.of(l);
    }
    if (v instanceof Number n) {
      return Optional.of(n.longValue());
    }
    return Optional.empty();
  }

  /**
   * The entire decoded envelope, including any key this class does not model — {@code facets} and
   * {@code tier} on search, for instance.
   */
  public Map<String, Object> raw() {
    return raw;
  }

  /** Number of rows on this page. */
  public int size() {
    return items.size();
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  @Override
  public String toString() {
    return "Page[items=" + items.size() + ", nextCursor=" + nextCursor + "]";
  }

  /**
   * Build a page from a decoded response body.
   *
   * @throws com.chatlychat.chatly.errors.ChatlyException if the body is not a page envelope
   */
  @SuppressWarnings("unchecked") // element shape is checked per-row below
  static Page<Map<String, Object>> fromDecoded(Object decoded, String path) {
    if (!(decoded instanceof Map<?, ?> envelope)) {
      throw new com.chatlychat.chatly.errors.ChatlyException(
          "expected a paginated envelope {items, nextCursor} from "
              + path
              + " but got "
              + Json.describe(decoded),
          0,
          null,
          null,
          null,
          null);
    }
    Object itemsValue = envelope.get("items");
    if (!(itemsValue instanceof List<?> rows)) {
      throw new com.chatlychat.chatly.errors.ChatlyException(
          "the response from " + path + " has no `items` array", 0, null, null, null, null);
    }
    List<Map<String, Object>> typed = new java.util.ArrayList<>(rows.size());
    for (Object row : rows) {
      if (row instanceof Map<?, ?> m) {
        typed.add((Map<String, Object>) m);
      } else {
        // A list of scalars is not something any Chatly route returns; wrapping
        // keeps the row reachable instead of dropping it silently.
        typed.add(Map.of("value", row));
      }
    }
    Object cursor = envelope.get("nextCursor");
    String nextCursor = cursor instanceof String s && !s.isEmpty() ? s : null;
    return new Page<>(typed, nextCursor, (Map<String, Object>) envelope);
  }
}
