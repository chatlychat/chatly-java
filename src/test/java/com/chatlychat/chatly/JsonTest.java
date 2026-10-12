package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The hand-written codec, tested directly.
 *
 * <p>Writing our own JSON is what buys the zero-dependency promise, and the price of that promise is
 * this file. Everything the SDK relies on is asserted here rather than inferred from the fact that
 * a request happened to work.
 */
class JsonTest {

  @Nested
  class Writing {

    @Test
    void scalars() {
      assertEquals("null", Json.write(null));
      assertEquals("true", Json.write(true));
      assertEquals("false", Json.write(false));
      assertEquals("42", Json.write(42));
      assertEquals("42", Json.write(42L));
      assertEquals("-7", Json.write(-7));
      assertEquals("\"hi\"", Json.write("hi"));
      assertEquals("\"\"", Json.write(""));
    }

    @Test
    void objectsKeepInsertionOrder() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("z", 1);
      m.put("a", 2);
      m.put("m", 3);
      assertEquals("{\"z\":1,\"a\":2,\"m\":3}", Json.write(m));
    }

    @Test
    void nestedStructures() {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("externalId", "u-1");
      body.put("attributes", Map.of("plan", "pro"));
      body.put("tags", List.of("vip", "beta"));
      assertEquals(
          "{\"externalId\":\"u-1\",\"attributes\":{\"plan\":\"pro\"},\"tags\":[\"vip\",\"beta\"]}",
          Json.write(body));
    }

    @Test
    void emptyContainers() {
      assertEquals("{}", Json.write(Map.of()));
      assertEquals("[]", Json.write(List.of()));
    }

    @Test
    void stringEscaping() {
      assertEquals("\"a\\\"b\"", Json.write("a\"b"));
      assertEquals("\"a\\\\b\"", Json.write("a\\b"));
      assertEquals("\"a\\nb\"", Json.write("a\nb"));
      assertEquals("\"a\\tb\"", Json.write("a\tb"));
      assertEquals("\"a\\rb\"", Json.write("a\rb"));
      assertEquals("\"\\b\\f\"", Json.write("\b\f"));
    }

    /**
     * Raw C0 control characters are forbidden inside a JSON string by RFC 8259 and must be escaped.
     *
     * <p>Not cosmetic: an unescaped {@code \u0001} in a message body produces a body the server's
     * parser rejects with a 400 that looks like a schema failure, and the character is invisible in
     * every log line you would read while debugging it.
     */
    @Test
    void controlCharactersAreEscaped() {
      assertEquals("\"\\u0000\"", Json.write("\u0000"));
      assertEquals("\"\\u0001\"", Json.write("\u0001"));
      assertEquals("\"\\u001f\"", Json.write("\u001f"));
      assertEquals("\" \"", Json.write("\u0020"), "0x20 is a printable space, not a control char");
    }

    @Test
    void nonAsciiIsSentLiterallyAsUtf8() {
      assertEquals("\"café ☕\"", Json.write("café ☕"));
      assertEquals("\"🙂\"", Json.write("🙂"), "a surrogate pair survives intact");
    }

    @Test
    void arraysAndEnumsAndChars() {
      assertEquals("[1,2]", Json.write(new Object[] {1, 2}));
      assertEquals("\"SECONDS\"", Json.write(java.util.concurrent.TimeUnit.SECONDS));
      assertEquals("\"x\"", Json.write('x'));
    }

    @Test
    void doublesAreWrittenAsJsonNumbers() {
      assertEquals("1.5", Json.write(1.5));
      assertEquals("1.0", Json.write(1.0d), "an integral double stays a double; narrowing it would"
          + " change the type the server's schema sees");
    }

    @Test
    void nanAndInfinityAreRefusedRatherThanEmittedAsInvalidJson() {
      assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
      assertThrows(IllegalArgumentException.class, () -> Json.write(Double.POSITIVE_INFINITY));
    }

    @Test
    void anUnencodableTypeIsAnErrorNotAToStringCall() {
      // Silently toString()-ing an arbitrary object produces a field the server
      // rejects in a way nobody can trace back to this codec.
      assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
      assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of(1, "non-string key")));
    }

    @Test
    void aCycleIsAnExceptionNotAStackOverflow() {
      List<Object> cycle = new ArrayList<>();
      cycle.add(cycle);
      assertThrows(IllegalArgumentException.class, () -> Json.write(cycle));
    }
  }

  @Nested
  class Reading {

    @Test
    void scalars() {
      assertNull(Json.parse("null"));
      assertEquals(Boolean.TRUE, Json.parse("true"));
      assertEquals(Boolean.FALSE, Json.parse("false"));
      assertEquals("hi", Json.parse("\"hi\""));
      assertEquals(42L, Json.parse("42"));
      assertEquals(-42L, Json.parse("-42"));
      assertEquals(0L, Json.parse("0"));
    }

    @Test
    void integralNumbersAreLongsAndFractionalOnesAreDoubles() {
      assertInstanceOf(Long.class, Json.parse("7"));
      assertInstanceOf(Double.class, Json.parse("7.5"));
      assertInstanceOf(Double.class, Json.parse("7e2"));
      assertInstanceOf(Double.class, Json.parse("7E-2"));
      assertEquals(750.0, (Double) Json.parse("7.5e2"), 1e-9);
    }

    @Test
    void anIntegralNumberTooWideForALongBecomesADouble() {
      assertInstanceOf(Double.class, Json.parse("123456789012345678901234567890"));
    }

    @Test
    void objectsAndArrays() {
      assertEquals(Map.of("a", 1L), Json.parse("{\"a\":1}"));
      assertEquals(List.of(1L, 2L, 3L), Json.parse("[1,2,3]"));
      assertEquals(Map.of(), Json.parse("{}"));
      assertEquals(List.of(), Json.parse("[]"));
    }

    @Test
    void objectKeyOrderIsPreserved() {
      Map<String, Object> parsed = Json.parseObject("{\"z\":1,\"a\":2}");
      assertEquals(List.of("z", "a"), new ArrayList<>(parsed.keySet()));
    }

    @Test
    void whitespaceIsSkipped() {
      assertEquals(
          Map.of("a", List.of(1L, 2L)), Json.parse("  {\n \"a\" : [ 1 ,\t2 ]\r\n }  "));
    }

    @Test
    void escapeSequences() {
      assertEquals("a\"b", Json.parse("\"a\\\"b\""));
      assertEquals("a\\b", Json.parse("\"a\\\\b\""));
      assertEquals("a/b", Json.parse("\"a\\/b\""));
      assertEquals("a\nb", Json.parse("\"a\\nb\""));
      assertEquals("\b\f\r\t", Json.parse("\"\\b\\f\\r\\t\""));
      assertEquals("\u00e9", Json.parse("\"\\u00e9\""));
      assertEquals("A", Json.parse("\"\\u0041\""));
    }

    /**
     * A surrogate pair must be joined.
     *
     * <p>Otherwise an emoji in a visitor's message comes back as two replacement characters — a bug
     * a customer sees in an agent's inbox and which no amount of structural testing finds.
     */
    @Test
    void surrogatePairsAreJoined() {
      assertEquals("🙂", Json.parse("\"\\ud83d\\ude42\""));
      assertEquals("a🙂b", Json.parse("\"a\\ud83d\\ude42b\""));
      assertEquals(1, ((String) Json.parse("\"\\ud83d\\ude42\"")).codePointCount(0, 2));
    }

    @Test
    void aLoneHighSurrogateIsPassedThroughRatherThanFailingTheWholeResponse() {
      String out = (String) Json.parse("\"\\ud83d\"");
      assertEquals(1, out.length());
      assertTrue(Character.isHighSurrogate(out.charAt(0)));
    }

    @Test
    void roundTrip() {
      String original =
          "{\"id\":\"c_1\",\"name\":\"Jamie\",\"n\":3,\"ok\":true,\"x\":null,"
              + "\"tags\":[\"a\",\"b\"],\"nested\":{\"k\":\"v\"}}";
      assertEquals(original, Json.write(Json.parse(original)));
    }

    @Test
    void duplicateKeysTakeTheLastValue() {
      // Matches every mainstream parser and the JS one the API is written
      // against. Written down because it is not in the spec.
      assertEquals(Map.of("a", 2L), Json.parse("{\"a\":1,\"a\":2}"));
    }

    @Test
    void parseObjectAndParseArrayCheckTheTopLevelShape() {
      assertEquals(Map.of("a", 1L), Json.parseObject("{\"a\":1}"));
      assertEquals(List.of(1L), Json.parseArray("[1]"));
      assertThrows(Json.JsonParseException.class, () -> Json.parseObject("[1]"));
      assertThrows(Json.JsonParseException.class, () -> Json.parseArray("{\"a\":1}"));
      assertThrows(Json.JsonParseException.class, () -> Json.parseObject("\"a string\""));
    }
  }

  @Nested
  class Rejecting {

    private void bad(String text) {
      Json.JsonParseException e =
          assertThrows(
              Json.JsonParseException.class, () -> Json.parse(text), "should have rejected: " + text);
      assertTrue(e.offset() >= 0);
      assertTrue(e.getMessage().contains("offset"), e.getMessage());
    }

    @Test
    void malformedDocuments() {
      bad("");
      bad("   ");
      bad("{");
      bad("}");
      bad("[");
      bad("]");
      bad("{\"a\"}");
      bad("{\"a\":}");
      bad("{a:1}");
      bad("{'a':1}");
      bad("[1,]");
      bad("{\"a\":1,}");
      bad("[1 2]");
      bad("tru");
      bad("nul");
      bad("undefined");
      bad("NaN");
      bad("Infinity");
    }

    @Test
    void malformedNumbers() {
      bad("01");
      bad("-");
      bad("1.");
      bad(".5");
      bad("1e");
      bad("1e+");
      bad("--1");
      bad("+1");
      bad("1.2.3");
    }

    @Test
    void malformedStrings() {
      bad("\"unterminated");
      bad("\"bad \\q escape\"");
      bad("\"truncated \\u00\"");
      bad("\"not hex \\uzzzz\"");
      bad("\"raw \u0001 control\"");
      bad("\"raw newline\n\"");
    }

    /**
     * Trailing content is an error, not something to ignore.
     *
     * <p>A body that parses as {@code {"a":1}} followed by garbage is a truncated or doubled
     * response. Reading only the first half of it is how a corrupted response becomes a
     * plausible-looking object that something downstream then acts on.
     */
    @Test
    void trailingContentIsRefused() {
      bad("{\"a\":1} trailing");
      bad("{\"a\":1}{\"b\":2}");
      bad("[1][2]");
      bad("1 2");
    }

    @Test
    void aBomIsNotSilentlySkipped() {
      bad("\ufeff{\"a\":1}");
    }

    @Test
    void absurdNestingIsAnExceptionNotAStackOverflow() {
      // A few kilobytes of '[' would otherwise be a StackOverflowError, which
      // is not catchable in any useful sense.
      String deep = "[".repeat(5000) + "]".repeat(5000);
      assertThrows(Json.JsonParseException.class, () -> Json.parse(deep));
    }

    @Test
    void nullInputIsRefused() {
      assertThrows(Json.JsonParseException.class, () -> Json.parse(null));
    }
  }

  @Nested
  class Describing {

    @Test
    void typeNamesForErrorMessages() {
      assertEquals("an object", Json.describe(Map.of()));
      assertEquals("an array", Json.describe(List.of()));
      assertEquals("a string", Json.describe("x"));
      assertEquals("a number", Json.describe(1L));
      assertEquals("a boolean", Json.describe(true));
      assertEquals("null", Json.describe(null));
    }
  }

  @Nested
  class QueryEncoding {

    @Test
    void repeatedKeysStayRepeated() {
      Query q = Query.of().add("kinds[]", "kb").add("kinds[]", "article");
      assertEquals("kinds%5B%5D=kb&kinds%5B%5D=article", q.encode());
    }

    @Test
    void nullValuesAreDropped() {
      assertEquals("", Query.of().add("cursor", null).encode());
      assertTrue(Query.of().cursor(null).isEmpty());
    }

    @Test
    void setReplacesAndAddAppends() {
      assertEquals("a=2", Query.of().add("a", 1).set("a", 2).encode());
      assertEquals("a=1&a=2", Query.of().add("a", 1).add("a", 2).encode());
    }

    @Test
    void copyIsIndependent() {
      Query original = Query.of().limit(10);
      Query derived = original.copy().cursor("cur_2");
      assertFalse(original.encode().contains("cursor"));
      assertTrue(derived.encode().contains("cursor=cur_2"));
    }

    @Test
    void valuesArePercentEncoded() {
      assertEquals("q=a+b%26c%3Dd", Query.of().add("q", "a b&c=d").encode());
    }

    @Test
    void pathSegmentsUseRfc3986NotFormEncoding() {
      assertEquals("needs%20follow%20up", Query.encodePathSegment("needs follow up"));
      assertEquals("a%2Fb", Query.encodePathSegment("a/b"));
      assertEquals("caf%C3%A9", Query.encodePathSegment("café"));
      assertEquals("plain-id_1.2~3", Query.encodePathSegment("plain-id_1.2~3"));
      assertEquals("50%25%2B", Query.encodePathSegment("50%+"));
    }

    @Test
    void anEmptyParameterNameIsRefused() {
      assertThrows(IllegalArgumentException.class, () -> Query.of().add("", "x"));
    }
  }
}
