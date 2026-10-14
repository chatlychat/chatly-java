package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Channels — the mediums conversations arrive on: web widget, email, WhatsApp, SMS and the rest.
 *
 * <p>Reached as {@code chatly.channels()}.
 *
 * <p>This class's documentation used to say channels were read-only from a key, because
 * "{@code channels:write} … no API-key-accessible route grants". That was wrong:
 * {@link #create}, {@link #update} and {@link #rotateSecret} all carry {@code @AllowApiKey()} and
 * all take {@code channels:write}. A key granted that scope can configure channels; a key without
 * it gets a 403 from these three and still reads {@link #list()} and {@link #get} on
 * {@code channels:read}.
 */
public final class ChannelsResource extends Resource {

  private static final String COLLECTION = "/v1/channels";

  ChannelsResource(Transport transport) {
    super(transport);
  }

  /**
   * Every channel. {@code GET /v1/channels} — a bare array.
   *
   * <p>Secrets are redacted here. {@code GET /v1/channels/{id}/secret} reveals one and is
   * deliberately <em>not</em> on this SDK's surface.
   *
   * <p>Worth reading {@code originsRestricted} on a web channel: when a channel has no origin
   * allow-list configured it accepts an embed from <em>any</em> site, and an empty list is
   * indistinguishable from an absent one in the raw JSON, so the API sends that boolean to make the
   * distinction visible.
   */
  public List<Map<String, Object>> list() {
    return array(transport.get(COLLECTION, Query.of()), COLLECTION);
  }

  /** One channel by id. {@code GET /v1/channels/{id}} */
  public Map<String, Object> get(String channelId) {
    String path = COLLECTION + "/" + seg(channelId, "channelId");
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * The channel types this deployment can serve. {@code GET /v1/channels/adapters}
   *
   * <p><strong>The one route on this SDK's surface whose array holds strings rather than
   * objects</strong> ({@code z.array(z.string())}), which is why it returns {@code List<String>}
   * and not {@code List<Map<String,Object>>}. A decoder built only for arrays of objects fails on
   * exactly this row — the manifest says so, and it is the kind of thing a client discovers in
   * production.
   *
   * <p>Read it before {@link #create}: the type enum in the request schema is a superset of what a
   * given deployment has an adapter registered for, and a type with no adapter is a channel that
   * saves and never delivers.
   */
  public List<String> adapters() {
    String path = COLLECTION + "/adapters";
    return strings(transport.get(path, Query.of()), path);
  }

  /**
   * Create a channel. {@code POST /v1/channels}
   *
   * <p>Required: {@code type}, {@code name}, {@code publicId}. {@code enabled} defaults to true and
   * {@code config} to {@code {}} server-side. {@code brandId} defaults to the workspace's default
   * brand.
   *
   * <p>{@code type} and {@code publicId} are stable identifiers external systems depend on and
   * <strong>neither is changeable afterwards</strong> — {@link #update} refuses them — so a
   * {@code publicId} typo means creating a replacement channel, not fixing this one.
   *
   * <p>The config is validated against the type <em>before</em> the write, so an unsupported
   * carrier is a 400 rather than a rolled-back row. This route is also plan-limited: a workspace at
   * its channel ceiling gets a refusal here, not a quiet extra channel.
   */
  public Map<String, Object> create(Map<String, Object> channel) {
    return object(
        transport.post(COLLECTION, Query.of(), required(channel, "channel")), COLLECTION);
  }

  /**
   * Update a channel. {@code PATCH /v1/channels/{id}}
   *
   * <p>Partial, and it must change something: an empty patch is a 409 ("No fields to update"), not
   * a no-op 200. {@code type} and {@code publicId} are not accepted.
   *
   * <p>{@code config} is <strong>merged</strong> into the stored config rather than replacing it,
   * which is what you want for rotating one credential and not what you want if you meant to clear
   * a key. One field is special: changing a web channel's identity-verification secret keeps the
   * previous one verifiable for a grace window, so visitors mid-session are not logged out.
   */
  public Map<String, Object> update(String channelId, Map<String, Object> changes) {
    String path = COLLECTION + "/" + seg(channelId, "channelId");
    return object(transport.patch(path, Query.of(), required(changes, "changes")), path);
  }

  /**
   * Issue a new channel secret, invalidating the old one.
   *
   * <p>{@code POST /v1/channels/{id}/secret/rotate}
   *
   * <p><strong>Only a custom ({@code api}) channel has a Chatly-issued secret.</strong> Every other
   * type's credential is issued by the provider, so this answers 409 for them rather than
   * pretending to rotate something — an important distinction, because a caller that treats a 409
   * as "rotated" believes it has secured an integration it has not touched.
   *
   * <p>A hard cut with no grace window: the old secret stops authenticating the moment this
   * returns, and inbound on that channel fails until the new value is deployed. That is deliberate
   * — a second valid secret means a leaked one keeps working, which defeats rotating.
   *
   * <p>Why it matters that this exists at all: the custom channel's secret authenticates every
   * inbound message, and inbound carries an arbitrary {@code contactId}, so holding it means being
   * able to impersonate any contact in the workspace.
   */
  public Map<String, Object> rotateSecret(String channelId) {
    String path = COLLECTION + "/" + seg(channelId, "channelId") + "/secret/rotate";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }
}
