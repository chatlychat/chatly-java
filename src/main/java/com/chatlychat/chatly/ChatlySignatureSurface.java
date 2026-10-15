package com.chatlychat.chatly;

/**
 * Which outbound surfaces Chatly signs, and what each one signs.
 *
 * <p>A port of {@code packages/sdk-node/src/signature-table.ts}, which is the reference. The table
 * is the point:
 *
 * <pre>
 * | surface                 | header(s)                                           | base string                     | t unit  |
 * | unified_v2              | x-chatly-signature-v2: t=…,v1=…                     | &lt;t&gt;.&lt;METHOD&gt;.&lt;path&gt;.&lt;rawBody&gt; | seconds |
 * | agent_delivery          | x-chatly-signature: t=…,v1=…                        | &lt;t&gt;.&lt;rawBody&gt;                 | seconds |
 * | event_webhook           | x-livechat-signature: t=…,v1=…                      | &lt;t&gt;.&lt;rawBody&gt;                 | seconds |
 * | ai_tool_call            | x-chatly-signature: t=…,v1=…                        | &lt;t&gt;.&lt;METHOD&gt;.&lt;path&gt;.&lt;rawBody&gt; | seconds |
 * | canvas_step             | x-chatly-canvas-signature: sha256=… + -ts           | &lt;ts&gt;.&lt;rawBody&gt;                | MS      |
 * | customer_card           | x-chatly-signature: sha256=… + x-chatly-timestamp   | &lt;ts&gt;.&lt;rawBody&gt;                | MS      |
 * | workflow_webhook        | x-chatly-signature-v2: t=…,v1=…                     | &lt;t&gt;.&lt;rawBody&gt;                 | seconds |
 * | workflow_webhook_legacy | x-chatly-signature: sha256=…                        | &lt;rawBody&gt; — NO timestamp        | n/a     |
 * </pre>
 *
 * <p><strong>Three surfaces send {@code x-chatly-signature} and they sign three different
 * strings.</strong> That is why {@link Webhooks#verifyChatlySignature} takes a surface and never
 * infers one from the header name: a verifier keyed on the header can be pointed at the wrong
 * construction while looking completely correct.
 *
 * <p><strong>Two surfaces send MILLISECONDS.</strong> A millisecond timestamp read as seconds lands
 * roughly 54,000 years out, a tolerance check rejects every genuine request, and the failure looks
 * like a wrong secret. So the unit is a property of the SURFACE here, never of the value.
 *
 * <p>{@code workflow_webhook} sent {@code x-chatly-signature-v2} before the unified base gained the
 * method and path, so the same header name carries two base strings until that sender moves. It is
 * the one surface that also accepts the target base ({@link #alsoAcceptsTargetBase()}); the value's
 * {@code b=} marker narrows which one, and never widens it, since anyone on the wire can write a
 * marker. {@code unified_v2} is strict and accepts only the target base.
 *
 * <p>Kept as data rather than a {@code switch} so a test can assert every surface has a row; a
 * surface added to the reference and forgotten here would otherwise be an {@code unknown_surface}
 * refusal at runtime with a green suite.
 */
public enum ChatlySignatureSurface {
  UNIFIED_V2(
      "unified_v2",
      ChatlySignatureSurface.V2_SIGNATURE_HEADER,
      ChatlySignatureSurface.IN_SIGNATURE,
      Format.TV1,
      Unit.SECONDS,
      ChatlySignatureResult.Base.TARGET,
      false,
      "the unified v2 signature, sent alongside every surface’s legacy header, over "
          + "t.METHOD.path.body"),
  AGENT_DELIVERY(
      "agent_delivery",
      "x-chatly-signature",
      ChatlySignatureSurface.IN_SIGNATURE,
      Format.TV1,
      Unit.SECONDS,
      ChatlySignatureResult.Base.BODY,
      false,
      "external-agent push delivery, signed with the bot's signingSecret"),
  EVENT_WEBHOOK(
      "event_webhook",
      "x-livechat-signature",
      ChatlySignatureSurface.IN_SIGNATURE,
      Format.TV1,
      Unit.SECONDS,
      ChatlySignatureResult.Base.BODY,
      false,
      "event webhook, signed with that subscription's whsec_ secret"),
  AI_TOOL_CALL(
      "ai_tool_call",
      "x-chatly-signature",
      ChatlySignatureSurface.IN_SIGNATURE,
      Format.TV1,
      Unit.SECONDS,
      ChatlySignatureResult.Base.TARGET,
      false,
      "custom AI tool call, signed with that tool's secret over t.METHOD.path.body"),
  CANVAS_STEP(
      "canvas_step",
      "x-chatly-canvas-signature",
      "x-chatly-canvas-ts",
      Format.SHA256,
      Unit.MILLISECONDS,
      ChatlySignatureResult.Base.BODY,
      false,
      "canvas / interactive-step callback, timestamp in MILLISECONDS"),
  CUSTOMER_CARD(
      "customer_card",
      "x-chatly-signature",
      "x-chatly-timestamp",
      Format.SHA256,
      Unit.MILLISECONDS,
      ChatlySignatureResult.Base.BODY,
      false,
      "customer-card fetch, timestamp in MILLISECONDS"),
  WORKFLOW_WEBHOOK(
      "workflow_webhook",
      ChatlySignatureSurface.V2_SIGNATURE_HEADER,
      ChatlySignatureSurface.IN_SIGNATURE,
      Format.TV1,
      Unit.SECONDS,
      ChatlySignatureResult.Base.BODY,
      // The one surface that sent the v2 header before the base gained the
      // method and path. See this class's header.
      true,
      "workflow \"call a webhook\" action, replay-bounded v2 header"),
  WORKFLOW_WEBHOOK_LEGACY(
      "workflow_webhook_legacy",
      "x-chatly-signature",
      null,
      Format.SHA256,
      null,
      ChatlySignatureResult.Base.BODY_ONLY,
      false,
      "workflow action, LEGACY header over the body alone — no replay protection");

  /** Five minutes, matching {@code SIGNATURE_TOLERANCE_SECONDS} on the producing side. */
  public static final long TOLERANCE_SECONDS = 300L;

  /** The header the unified construction travels in, on every surface. */
  public static final String V2_SIGNATURE_HEADER = "x-chatly-signature-v2";

  /**
   * Not a header name: the timestamp is the digest header's own {@code t=} field. A sentinel rather
   * than a boolean, so a surface that moves its timestamp into a header of its own needs only a
   * table edit.
   */
  static final String IN_SIGNATURE = "in-signature";

  /** {@code t=…,v1=<hex>} or {@code sha256=<hex>}. */
  enum Format {
    TV1,
    SHA256
  }

  /** The unit of whatever the timestamp source yields. */
  enum Unit {
    SECONDS,
    MILLISECONDS
  }

  private final String wireName;
  private final String signatureHeader;
  private final String timestampFrom;
  private final Format format;
  private final Unit unit;
  private final ChatlySignatureResult.Base base;
  private final boolean alsoAcceptsTargetBase;
  private final String describe;

  ChatlySignatureSurface(
      String wireName,
      String signatureHeader,
      String timestampFrom,
      Format format,
      Unit unit,
      ChatlySignatureResult.Base base,
      boolean alsoAcceptsTargetBase,
      String describe) {
    this.wireName = wireName;
    this.signatureHeader = signatureHeader;
    this.timestampFrom = timestampFrom;
    this.format = format;
    this.unit = unit;
    this.base = base;
    this.alsoAcceptsTargetBase = alsoAcceptsTargetBase;
    this.describe = describe;
  }

  /** The stable string, e.g. {@code "event_webhook"} — identical in every Chatly SDK. */
  public String wireName() {
    return wireName;
  }

  /** The header carrying the digest. */
  public String signatureHeader() {
    return signatureHeader;
  }

  /**
   * The header carrying the timestamp on its own, or {@code null} when it is the digest header's
   * {@code t=} field or when the surface sends no timestamp at all.
   */
  public String timestampHeader() {
    return IN_SIGNATURE.equals(timestampFrom) ? null : timestampFrom;
  }

  /** True only for {@code canvas_step} and {@code customer_card}. */
  public boolean timestampInMilliseconds() {
    return unit == Unit.MILLISECONDS;
  }

  /** False only for {@code workflow_webhook_legacy}, whose digest can be replayed forever. */
  public boolean hasTimestamp() {
    return unit != null;
  }

  /** The string the MAC is taken over. */
  public ChatlySignatureResult.Base base() {
    return base;
  }

  /** True when the unified target base is also accepted on this surface's own header. */
  public boolean alsoAcceptsTargetBase() {
    return alsoAcceptsTargetBase;
  }

  /** A one-line description, for an error message and for documentation. */
  public String describe() {
    return describe;
  }

  String timestampFrom() {
    return timestampFrom;
  }

  Format format() {
    return format;
  }

  Unit unit() {
    return unit;
  }

  /**
   * The surface for a wire name, or {@code null} for anything else — including a different case.
   * {@code "Event_Webhook"} is not a surface in the reference, so it is not one here either.
   */
  public static ChatlySignatureSurface fromWireName(String wireName) {
    if (wireName == null) {
      return null;
    }
    for (ChatlySignatureSurface s : values()) {
      if (s.wireName.equals(wireName)) {
        return s;
      }
    }
    return null;
  }

  /** Every wire name, comma-separated, in table order. For error messages. */
  static String allWireNames() {
    StringBuilder out = new StringBuilder();
    for (ChatlySignatureSurface s : values()) {
      if (out.length() > 0) {
        out.append(", ");
      }
      out.append(s.wireName);
    }
    return out.toString();
  }

  @Override
  public String toString() {
    return wireName;
  }
}
