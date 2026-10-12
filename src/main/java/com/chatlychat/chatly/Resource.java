package com.chatlychat.chatly;

import com.chatlychat.chatly.errors.ChatlyException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared plumbing for the resource namespaces.
 *
 * <p>A resource class should contain nothing but a verb, a path, and a docblock. Everything that
 * could be got subtly wrong — path-segment encoding, response-shape checking, building a
 * {@link Pager} whose fetcher correctly carries the caller's other query parameters onto page two —
 * lives here, once.
 *
 * <p>Package-private: it is an implementation detail, not surface. A customer who wants to call a
 * route this SDK has no method for uses {@link Chatly#request} instead.
 */
abstract class Resource {

  protected final Transport transport;

  Resource(Transport transport) {
    this.transport = transport;
  }

  /** Percent-encode one path segment. */
  protected static String seg(String value, String name) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " must be a non-empty string");
    }
    return Query.encodePathSegment(value);
  }

  /** A query, never null. */
  protected static Query q(Query query) {
    return query == null ? Query.of() : query;
  }

  /**
   * Decode a response that must be a JSON object.
   *
   * <p>A shape mismatch is a {@link ChatlyException} and not a {@link ClassCastException} thrown
   * from somewhere in the caller's code two frames later: the message names the route, which is the
   * difference between "the SDK is wrong about {@code /v1/x}" and an unexplained cast failure.
   */
  @SuppressWarnings("unchecked") // guarded by the instanceof immediately above the cast
  protected static Map<String, Object> object(Object decoded, String path) {
    if (decoded == null) {
      return Map.of();
    }
    if (decoded instanceof Map) {
      return (Map<String, Object>) decoded;
    }
    throw new ChatlyException(
        "expected a JSON object from " + path + " but got " + Json.describe(decoded),
        0,
        "unexpected_response_shape",
        null,
        null,
        null);
  }

  /** Decode a response that must be a JSON array of objects. */
  @SuppressWarnings("unchecked") // each element is instanceof-checked before its cast
  protected static List<Map<String, Object>> array(Object decoded, String path) {
    if (decoded == null) {
      return List.of();
    }
    if (!(decoded instanceof List<?> rows)) {
      throw new ChatlyException(
          "expected a JSON array from " + path + " but got " + Json.describe(decoded),
          0,
          "unexpected_response_shape",
          null,
          null,
          null);
    }
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (Object row : rows) {
      out.add(row instanceof Map ? (Map<String, Object>) row : Map.of("value", row));
    }
    return List.copyOf(out);
  }

  /** Fetch one page. */
  protected Page<Map<String, Object>> page(String path, Query query) {
    return Page.fromDecoded(transport.get(path, q(query)), path);
  }

  /**
   * Build an auto-paginator over {@code path}.
   *
   * <p>The caller's query is copied once and the cursor is set per page on the copy, so filters and
   * {@code limit} survive onto page two and the caller's own {@link Query} object is never mutated
   * underneath them. Forgetting either is how an auto-paginator silently returns page one's filter
   * applied to an unfiltered page two.
   */
  protected Pager<Map<String, Object>> pager(String path, Query query) {
    Query base = q(query).copy();
    return new Pager<>(
        cursor -> {
          Query perPage = base.copy();
          if (cursor != null) {
            perPage.cursor(cursor);
          }
          return page(path, perPage);
        },
        path);
  }

  /** A mutable, order-preserving body map. */
  protected static Map<String, Object> body() {
    return new LinkedHashMap<>();
  }

  /** Put only if the value is non-null, so optional fields are omitted rather than sent as null. */
  protected static Map<String, Object> put(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
    return target;
  }

  /** Require a non-null, non-empty String argument. */
  protected static String required(String value, String name) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " is required and must be non-empty");
    }
    return value;
  }

  /** Require a non-null argument of any type. */
  protected static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
