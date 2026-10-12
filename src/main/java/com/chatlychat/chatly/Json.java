package com.chatlychat.chatly;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, hand-written JSON reader/writer.
 *
 * <p>This exists so the SDK has <strong>zero runtime dependencies</strong>. A customer who wants to
 * send a chat message should not inherit Jackson, Gson, or their transitive trees — and, more
 * sharply, should not be forced into a Jackson <em>version</em>, which is the single most common
 * dependency conflict on the JVM. The cost of that choice is this file: about 300 lines we own and
 * test directly ({@code JsonTest}) rather than 2MB we don't.
 *
 * <p>It is deliberately not a general-purpose JSON library. It implements RFC 8259 for the shapes
 * this API actually exchanges and nothing else:
 *
 * <ul>
 *   <li>Decoding maps onto plain JDK types: {@link LinkedHashMap} (key order preserved as received),
 *       {@link ArrayList}, {@link String}, {@link Long}, {@link Double}, {@link Boolean}, {@code null}.
 *   <li>An integral number that fits in a {@code long} decodes as {@link Long}; anything else —
 *       fractional, exponent, or out of {@code long} range — decodes as {@link Double}. There is no
 *       {@code BigDecimal} path, so do not route money through this codec without reading
 *       {@link #parse} first. The Chatly API returns monetary amounts as integer cents, which is
 *       exactly representable.
 *   <li>Encoding accepts {@link Map}, {@link Collection}, arrays, {@link String}, {@link Number},
 *       {@link Boolean}, {@code null}, and {@link Enum} (by {@code name()}). Anything else is a
 *       programming error and throws, rather than being silently {@code toString()}'d into a field
 *       the server will reject in a way nobody can trace back to here.
 * </ul>
 *
 * <p>There is no reflective object binding. Requests are built from maps; responses are read as
 * maps. That is a real ergonomic cost versus Jackson and it is the honest trade: see the README's
 * "Why maps and not POJOs" section before deciding this is a bug.
 */
public final class Json {

  private Json() {}

  // ---------------------------------------------------------------- encoding

  /** Serialise a value to a compact JSON string. */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    writeValue(out, value, 0);
    return out.toString();
  }

  /**
   * Nesting limit, applied to both directions.
   *
   * <p>On the write side it stops a self-referential map from being a stack-overflow instead of an
   * exception. On the read side it stops a hostile or broken response body (a few kilobytes of
   * {@code [[[[[…}) from taking the whole JVM down with a {@link StackOverflowError}, which is not
   * catchable in any useful sense. 64 is far deeper than any Chatly response.
   */
  private static final int MAX_DEPTH = 64;

  private static void writeValue(StringBuilder out, Object value, int depth) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException("Json.write: value nested deeper than " + MAX_DEPTH
          + " levels (a cycle in the object graph looks exactly like this)");
    }
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      writeString(out, s);
    } else if (value instanceof Boolean b) {
      out.append(b.booleanValue() ? "true" : "false");
    } else if (value instanceof Number n) {
      writeNumber(out, n);
    } else if (value instanceof Map<?, ?> m) {
      writeObject(out, m, depth);
    } else if (value instanceof Collection<?> c) {
      writeArray(out, c, depth);
    } else if (value instanceof Object[] arr) {
      writeArray(out, List.of(arr), depth);
    } else if (value instanceof Enum<?> e) {
      writeString(out, e.name());
    } else if (value instanceof Character c) {
      writeString(out, String.valueOf(c));
    } else {
      throw new IllegalArgumentException(
          "Json.write: no JSON representation for " + value.getClass().getName()
              + ". Pass a Map, Collection, String, Number, Boolean, Enum or null.");
    }
  }

  private static void writeNumber(StringBuilder out, Number n) {
    if (n instanceof Double || n instanceof Float) {
      double d = n.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        // JSON has no literal for either. Emitting `NaN` produces a body the
        // server's parser rejects with a 400 that reads like a schema error.
        throw new IllegalArgumentException("Json.write: " + d + " is not representable in JSON");
      }
      // Integral doubles print as `1.0`, not `1`, and that is correct JSON; we
      // do not normalise, because narrowing 1.0 -> 1 would change the type the
      // server's zod schema sees.
      out.append(d);
    } else {
      out.append(n.toString());
    }
  }

  private static void writeObject(StringBuilder out, Map<?, ?> map, int depth) {
    out.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> e : map.entrySet()) {
      Object k = e.getKey();
      if (!(k instanceof String key)) {
        throw new IllegalArgumentException(
            "Json.write: object keys must be Strings, got "
                + (k == null ? "null" : k.getClass().getName()));
      }
      if (!first) {
        out.append(',');
      }
      first = false;
      writeString(out, key);
      out.append(':');
      writeValue(out, e.getValue(), depth + 1);
    }
    out.append('}');
  }

  private static void writeArray(StringBuilder out, Collection<?> items, int depth) {
    out.append('[');
    boolean first = true;
    for (Object item : items) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeValue(out, item, depth + 1);
    }
    out.append(']');
  }

  private static void writeString(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          // Every C0 control character must be escaped; RFC 8259 forbids them
          // raw inside a string. We leave everything else, including non-BMP
          // surrogate pairs, as literal UTF-8 — the transport sends UTF-8 and
          // escaping would only inflate the body.
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  // ---------------------------------------------------------------- decoding

  /**
   * Parse a complete JSON document.
   *
   * <p>Returns one of: {@code Map<String,Object>}, {@code List<Object>}, {@link String},
   * {@link Long}, {@link Double}, {@link Boolean}, or {@code null}.
   *
   * <p>Trailing content after the top-level value is an error, not something to ignore — a body that
   * parses as {@code {"a":1}} followed by garbage is a truncated or doubled response, and reading
   * only the first half of it is how a corrupted response becomes a plausible-looking object.
   *
   * @throws JsonParseException on any malformed input
   */
  public static Object parse(String text) {
    if (text == null) {
      throw new JsonParseException("Json.parse: input is null", 0);
    }
    Parser p = new Parser(text);
    p.skipWhitespace();
    Object value = p.readValue(0);
    p.skipWhitespace();
    if (!p.atEnd()) {
      throw new JsonParseException(
          "Json.parse: unexpected trailing content after the top-level value", p.pos);
    }
    return value;
  }

  /**
   * Parse a document that must be a JSON object.
   *
   * @throws JsonParseException if the document is valid JSON but not an object
   */
  @SuppressWarnings("unchecked") // checked immediately below by instanceof
  public static Map<String, Object> parseObject(String text) {
    Object v = parse(text);
    if (!(v instanceof Map)) {
      throw new JsonParseException(
          "Json.parseObject: expected a JSON object, got " + describe(v), 0);
    }
    return (Map<String, Object>) v;
  }

  /**
   * Parse a document that must be a JSON array.
   *
   * @throws JsonParseException if the document is valid JSON but not an array
   */
  @SuppressWarnings("unchecked") // checked immediately below by instanceof
  public static List<Object> parseArray(String text) {
    Object v = parse(text);
    if (!(v instanceof List)) {
      throw new JsonParseException("Json.parseArray: expected a JSON array, got " + describe(v), 0);
    }
    return (List<Object>) v;
  }

  /** Human-readable type name, for error messages. */
  static String describe(Object v) {
    if (v == null) {
      return "null";
    }
    if (v instanceof Map) {
      return "an object";
    }
    if (v instanceof List) {
      return "an array";
    }
    if (v instanceof String) {
      return "a string";
    }
    if (v instanceof Boolean) {
      return "a boolean";
    }
    if (v instanceof Number) {
      return "a number";
    }
    return v.getClass().getSimpleName();
  }

  /** Thrown when a response body is not the JSON we were promised. */
  public static final class JsonParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int offset;

    JsonParseException(String message, int offset) {
      super(message + " (at offset " + offset + ")");
      this.offset = offset;
    }

    /** Character offset into the document where parsing failed. */
    public int offset() {
      return offset;
    }
  }

  private static final class Parser {
    private final String src;
    private int pos;

    Parser(String src) {
      this.src = src;
      this.pos = 0;
    }

    boolean atEnd() {
      return pos >= src.length();
    }

    void skipWhitespace() {
      while (pos < src.length()) {
        char c = src.charAt(pos);
        // RFC 8259 §2: only these four are whitespace. Notably NOT   or
        // ﻿ — a leading BOM is a malformed document, and quietly skipping
        // it hides a mis-encoded response.
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          pos++;
        } else {
          break;
        }
      }
    }

    Object readValue(int depth) {
      if (depth > MAX_DEPTH) {
        throw new JsonParseException(
            "Json.parse: nesting deeper than " + MAX_DEPTH + " levels", pos);
      }
      if (atEnd()) {
        throw new JsonParseException("Json.parse: unexpected end of input", pos);
      }
      char c = src.charAt(pos);
      return switch (c) {
        case '{' -> readObject(depth);
        case '[' -> readArray(depth);
        case '"' -> readString();
        case 't' -> readLiteral("true", Boolean.TRUE);
        case 'f' -> readLiteral("false", Boolean.FALSE);
        case 'n' -> readLiteral("null", null);
        default -> {
          if (c == '-' || (c >= '0' && c <= '9')) {
            yield readNumber();
          }
          throw new JsonParseException("Json.parse: unexpected character '" + c + "'", pos);
        }
      };
    }

    private Object readLiteral(String word, Object value) {
      if (!src.startsWith(word, pos)) {
        throw new JsonParseException("Json.parse: expected '" + word + "'", pos);
      }
      pos += word.length();
      return value;
    }

    private Map<String, Object> readObject(int depth) {
      expect('{');
      Map<String, Object> out = new LinkedHashMap<>();
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        return out;
      }
      while (true) {
        skipWhitespace();
        if (peek() != '"') {
          throw new JsonParseException("Json.parse: expected a quoted object key", pos);
        }
        String key = readString();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        Object value = readValue(depth + 1);
        // Last duplicate key wins. That matches every mainstream JSON parser
        // and the JS one the API itself is written against; it is not in the
        // spec, so it is written down here rather than left to chance.
        out.put(key, value);
        skipWhitespace();
        char c = peek();
        if (c == ',') {
          pos++;
          continue;
        }
        if (c == '}') {
          pos++;
          return out;
        }
        throw new JsonParseException("Json.parse: expected ',' or '}' in object", pos);
      }
    }

    private List<Object> readArray(int depth) {
      expect('[');
      List<Object> out = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        pos++;
        return out;
      }
      while (true) {
        skipWhitespace();
        out.add(readValue(depth + 1));
        skipWhitespace();
        char c = peek();
        if (c == ',') {
          pos++;
          continue;
        }
        if (c == ']') {
          pos++;
          return out;
        }
        throw new JsonParseException("Json.parse: expected ',' or ']' in array", pos);
      }
    }

    private String readString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (atEnd()) {
          throw new JsonParseException("Json.parse: unterminated string", pos);
        }
        char c = src.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\') {
          if (atEnd()) {
            throw new JsonParseException("Json.parse: unterminated escape", pos);
          }
          char esc = src.charAt(pos++);
          switch (esc) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'u' -> sb.append(readUnicodeEscape());
            default ->
                throw new JsonParseException("Json.parse: invalid escape '\\" + esc + "'", pos - 1);
          }
          continue;
        }
        if (c < 0x20) {
          throw new JsonParseException(
              "Json.parse: raw control character U+" + String.format("%04X", (int) c)
                  + " inside a string",
              pos - 1);
        }
        sb.append(c);
      }
    }

    /**
     * Read one {@code \\uXXXX} escape and return the char(s) it denotes.
     *
     * <p>Surrogate pairs arrive as two consecutive escapes and must be joined, otherwise an emoji
     * in a visitor's message comes back as two replacement characters — a bug customers see and
     * nobody tests for. A lone surrogate is passed through as-is: it is ill-formed, but refusing
     * the whole response because one message body is mis-encoded is worse than surfacing it.
     */
    private char[] readUnicodeEscape() {
      char first = readHex4();
      if (Character.isHighSurrogate(first)
          && pos + 1 < src.length()
          && src.charAt(pos) == '\\'
          && src.charAt(pos + 1) == 'u') {
        int save = pos;
        pos += 2;
        char second = readHex4();
        if (Character.isLowSurrogate(second)) {
          return new char[] {first, second};
        }
        pos = save;
      }
      return new char[] {first};
    }

    private char readHex4() {
      if (pos + 4 > src.length()) {
        throw new JsonParseException("Json.parse: truncated \\u escape", pos);
      }
      int v = 0;
      for (int i = 0; i < 4; i++) {
        char c = src.charAt(pos + i);
        int d = Character.digit(c, 16);
        if (d < 0) {
          throw new JsonParseException("Json.parse: '" + c + "' is not a hex digit", pos + i);
        }
        v = (v << 4) | d;
      }
      pos += 4;
      return (char) v;
    }

    private Number readNumber() {
      int start = pos;
      if (peek() == '-') {
        pos++;
      }
      // Integer part. JSON forbids leading zeros ("01") and requires at least
      // one digit; both are enforced so a malformed body fails here rather
      // than silently truncating at the zero.
      if (atEnd()) {
        throw new JsonParseException("Json.parse: truncated number", pos);
      }
      char c = src.charAt(pos);
      if (c == '0') {
        pos++;
      } else if (c >= '1' && c <= '9') {
        while (pos < src.length() && isDigit(src.charAt(pos))) {
          pos++;
        }
      } else {
        throw new JsonParseException("Json.parse: expected a digit in number", pos);
      }

      boolean integral = true;
      if (pos < src.length() && src.charAt(pos) == '.') {
        integral = false;
        pos++;
        if (pos >= src.length() || !isDigit(src.charAt(pos))) {
          throw new JsonParseException("Json.parse: expected a digit after '.'", pos);
        }
        while (pos < src.length() && isDigit(src.charAt(pos))) {
          pos++;
        }
      }
      if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
        integral = false;
        pos++;
        if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
          pos++;
        }
        if (pos >= src.length() || !isDigit(src.charAt(pos))) {
          throw new JsonParseException("Json.parse: expected a digit in exponent", pos);
        }
        while (pos < src.length() && isDigit(src.charAt(pos))) {
          pos++;
        }
      }

      String lexeme = src.substring(start, pos);
      if (integral) {
        try {
          return Long.valueOf(lexeme);
        } catch (NumberFormatException overflow) {
          // Integral but wider than a long. Falling through to Double loses
          // precision, which is the lesser evil versus failing the whole
          // response; no Chatly identifier is a number, so this is reachable
          // only for a very large count.
          return Double.valueOf(lexeme);
        }
      }
      return Double.valueOf(lexeme);
    }

    private static boolean isDigit(char c) {
      return c >= '0' && c <= '9';
    }

    private char peek() {
      if (atEnd()) {
        throw new JsonParseException("Json.parse: unexpected end of input", pos);
      }
      return src.charAt(pos);
    }

    private void expect(char expected) {
      if (atEnd() || src.charAt(pos) != expected) {
        throw new JsonParseException("Json.parse: expected '" + expected + "'", pos);
      }
      pos++;
    }
  }
}
