package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Channels — the mediums conversations arrive on: web widget, email, WhatsApp, SMS and the rest.
 *
 * <p>Reached as {@code chatly.channels()}. Read-only: creating and configuring a channel needs
 * {@code channels:write}, which no API-key-accessible route grants, so there is no {@code create}
 * or {@code update} here. A method for either would compile, ship, and then 403 in somebody's
 * production integration.
 */
public final class ChannelsResource extends Resource {

  private static final String COLLECTION = "/v1/channels";

  ChannelsResource(Transport transport) {
    super(transport);
  }

  /**
   * Every channel. {@code GET /v1/channels} — a bare array.
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
}
