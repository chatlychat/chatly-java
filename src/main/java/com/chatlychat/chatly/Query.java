package com.chatlychat.chatly;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered, <strong>multi-valued</strong> query string builder.
 *
 * <p>Multi-valued is the whole point. The Chatly API takes repeated keys as repeated keys —
 * {@code kinds=kb&kinds=ticket} is two parameters, and comma-joining them into
 * {@code kinds=kb,ticket} asks for one kind literally named {@code "kb,ticket"}, which is not a
 * member of the server's enum and is a 400. A {@code Map<String,String>} cannot
 * express this, so the SDK does not use one.
 *
 * <p>Ordering is preserved so that a request is reproducible from its log line.
 *
 * <p>Instances are mutable and the setters chain:
 *
 * <pre>{@code
 * Query q = Query.of().limit(50).add("filter[]", "status:open").add("filter[]", "channel:web");
 * }</pre>
 */
public final class Query {

  private final Map<String, List<String>> params = new LinkedHashMap<>();

  private Query() {}

  /** An empty query. */
  public static Query of() {
    return new Query();
  }

  /** An empty query — alias that reads better at a call site that is about to add nothing. */
  public static Query none() {
    return new Query();
  }

  /**
   * Add a parameter, keeping any value already present under the same key.
   *
   * <p>A {@code null} value is dropped rather than sent as the string {@code "null"}: that makes
   * {@code .add("cursor", cursorOrNull)} safe, and sending the four characters {@code null} as a
   * cursor is a request the server cannot do anything sensible with.
   */
  public Query add(String name, Object value) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("Query.add: name must be non-empty");
    }
    if (value == null) {
      return this;
    }
    params.computeIfAbsent(name, k -> new ArrayList<>()).add(String.valueOf(value));
    return this;
  }

  /** Replace every value under {@code name} with this one. */
  public Query set(String name, Object value) {
    params.remove(name);
    return add(name, value);
  }

  /** Add every value under one key, in order. */
  public Query addAll(String name, Iterable<?> values) {
    if (values != null) {
      for (Object v : values) {
        add(name, v);
      }
    }
    return this;
  }

  /** {@code ?cursor=…} — the opaque page cursor from a previous {@link Page#nextCursor()}. */
  public Query cursor(String cursor) {
    return set("cursor", cursor);
  }

  /** {@code ?limit=…} — page size. */
  public Query limit(Integer limit) {
    return set("limit", limit);
  }

  /** {@code ?sort=…} — e.g. {@code -created_at}. Never sort on {@code id}; see {@link Page}. */
  public Query sort(String sort) {
    return set("sort", sort);
  }

  /** {@code ?fields=…} — sparse fieldset, comma-separated per the API's own convention. */
  public Query fields(String fields) {
    return set("fields", fields);
  }

  /** {@code ?filter[]=…}, repeatable. Call it once per filter. */
  public Query filter(String expression) {
    return add("filter[]", expression);
  }

  /** Whether anything has been added. */
  public boolean isEmpty() {
    return params.isEmpty();
  }

  /** Every value under one key, in insertion order; empty if absent. */
  public List<String> get(String name) {
    return List.copyOf(params.getOrDefault(name, List.of()));
  }

  /** A read-only snapshot, for inspection and tests. */
  public Map<String, List<String>> asMap() {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    params.forEach((k, v) -> copy.put(k, List.copyOf(v)));
    return Map.copyOf(copy);
  }

  /** A shallow copy, so a caller can derive a page-2 query without mutating page 1's. */
  public Query copy() {
    Query out = new Query();
    params.forEach((k, v) -> out.params.put(k, new ArrayList<>(v)));
    return out;
  }

  /**
   * Render as a query string <em>without</em> a leading {@code ?}. Empty string when empty.
   *
   * <p>Encoding is {@code application/x-www-form-urlencoded}: space becomes {@code +}, and
   * everything outside the unreserved set is percent-encoded, including the {@code []} in
   * {@code filter[]}. The API's query parser decodes both, so {@code filter%5B%5D} arrives as the
   * array parameter it is meant to be.
   */
  public String encode() {
    if (params.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, List<String>> e : params.entrySet()) {
      for (String v : e.getValue()) {
        if (sb.length() > 0) {
          sb.append('&');
        }
        sb.append(formEncode(e.getKey())).append('=').append(formEncode(v));
      }
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return encode();
  }

  private static String formEncode(String s) {
    return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  /**
   * Percent-encode one path segment.
   *
   * <p>Not {@code URLEncoder}: that targets form bodies, where a space is {@code +}. In a path a
   * {@code +} is a literal plus sign, so {@code URLEncoder}-ing a tag called {@code "needs follow
   * up"} into a path would look up a tag whose name contains plus signs. This encodes everything
   * outside RFC 3986's unreserved set, which is correct for a segment and never produces a
   * {@code /} that could escape into the route.
   *
   * <p>It matters for real inputs: {@code DELETE /v1/contacts/{id}/tags/{tag}} takes a human-typed
   * tag name.
   *
   * <p>Public because {@link Chatly#request} takes a path the caller assembled, and the caller needs
   * the same encoder the typed methods use. Hand-rolling it is how a tag with a slash in it
   * addresses a different route entirely.
   */
  public static String encodePathSegment(String segment) {
    StringBuilder out = new StringBuilder(segment.length() + 8);
    byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
    for (byte b : bytes) {
      int c = b & 0xFF;
      boolean unreserved =
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '.'
              || c == '_'
              || c == '~';
      if (unreserved) {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
      }
    }
    return out.toString();
  }
}
