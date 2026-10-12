package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Workspace-level configuration: settings, and the taxonomies and rosters conversations are filed
 * against.
 *
 * <p>Reached as {@code chatly.workspace()}.
 */
public final class WorkspaceResource extends Resource {

  WorkspaceResource(Transport transport) {
    super(transport);
  }

  /** The workspace's settings. {@code GET /v1/workspace/settings} — an object. */
  public Map<String, Object> settings() {
    String path = "/v1/workspace/settings";
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * The label taxonomy. {@code GET /v1/labels} — a bare array.
   *
   * <p>Not paginated on purpose: a label picker has to autocomplete over the whole set, and a
   * paginated taxonomy is an autocomplete that can only offer the first page.
   */
  public List<Map<String, Object>> labels() {
    String path = "/v1/labels";
    return array(transport.get(path, Query.of()), path);
  }

  /** The workspace's inboxes and their settings. {@code GET /v1/inboxes} — a bare array. */
  public List<Map<String, Object>> inboxes() {
    String path = "/v1/inboxes";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * The members of one team. {@code GET /v1/teams/{id}/members} — a bare array.
   *
   * <p>Not paginated: a team roster is a subset of the workspace's seats and the API renders it as
   * one list.
   *
   * <p>This is the only key-accessible route on the teams controller, so a backend integration needs
   * a team id from elsewhere — the dashboard, or a conversation's assignment — to use it.
   */
  public List<Map<String, Object>> teamMembers(String teamId) {
    String path = "/v1/teams/" + seg(teamId, "teamId") + "/members";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * The workspace's skill taxonomy. {@code GET /v1/agents/skills}
   *
   * <p>Returns an <strong>object</strong>, not an array: {@code {"skills": ["billing", "german"]}}.
   * The distinct skill labels in use across the workspace, which is what skill-based routing matches
   * against. Read them with:
   *
   * <pre>{@code
   * @SuppressWarnings("unchecked")
   * List<String> skills = (List<String>) chatly.workspace().agentSkills().get("skills");
   * }</pre>
   *
   * <p>Not paginated — it is a settings-style list, typically under 50 entries.
   *
   * <p>For the skills of one <em>specific</em> agent there is a sibling route that accepts a key but
   * is not on the typed surface; reach it through {@link Chatly#request}. For per-agent
   * <em>metrics</em> you want {@code chatly.reports().agents()} instead — the two are easy to
   * confuse because the paths differ by one prefix.
   */
  public Map<String, Object> agentSkills() {
    String path = "/v1/agents/skills";
    return object(transport.get(path, Query.of()), path);
  }
}
