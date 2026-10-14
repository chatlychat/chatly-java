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

  /**
   * Which workspaces the caller can reach. {@code GET /v1/workspaces/me} — a bare array.
   *
   * <p><strong>For an API key this is always exactly one entry</strong>: the workspace the key was
   * minted in. The route is the cross-workspace one — a person may belong to several — so for a
   * single-workspace credential it is a list of one, and that makes it the cheapest way to confirm
   * which tenant a key actually points at before writing to it.
   *
   * <p>The {@code role} on that entry is the synthetic {@code restricted} the auth layer mints for
   * every key, not a stored membership role. That is the honest value: what a key may do is its
   * scope set, which {@link Chatly#me()} answers. Reporting {@code owner} here because the person
   * who created the key was one would overstate the credential.
   *
   * <p>It answers even when the active workspace is <strong>suspended</strong>
   * ({@code @AllowWhenSuspended}), because this is how a caller discovers the others — without it a
   * user whose active workspace is suspended can switch but cannot see what to switch to.
   *
   * <p>It is the only open route on the workspaces controller. Creating a workspace makes the
   * caller its owner, which has no meaning without a person, so {@code POST /v1/workspaces} and the
   * rest stay closed to keys.
   */
  public List<Map<String, Object>> mine() {
    String path = "/v1/workspaces/me";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * Every scope a key can be granted. {@code GET /v1/api-keys/scopes} — a bare array.
   *
   * <p>Rows are {@code {value, group}}, where {@code group} is the part of the name before the
   * colon. Static, and built from the same constant the guard and the key service validate against,
   * so the documented vocabulary cannot drift from the real one.
   *
   * <p>This and {@code whoami()} are the only two routes on the api-keys controller a key may call
   * — managing keys with a key is a privilege-escalation shape. It is open because the question is
   * a key's own: it was once closed, and the 403 it returned pointed the caller at the very
   * endpoint it was refusing, so an integrator could only learn which scope a route needed by
   * provoking a 403 from it. Pair it with {@code whoami()} to turn "why am I getting a 403?" into a
   * lookup.
   */
  public List<Map<String, Object>> keyScopes() {
    String path = "/v1/api-keys/scopes";
    return array(transport.get(path, Query.of()), path);
  }

  /**
   * A census of every credential this workspace holds. {@code GET /v1/workspace/secrets}
   *
   * <p>Needs <strong>both</strong> {@code workspace:write} and {@code integrations:read} — the only
   * route on this SDK's surface that requires two scopes, so a key with one of them gets a 403 that
   * does not say which is missing. Compare against {@link #keyScopes()} and {@code whoami()}.
   *
   * <p>One entry per stored credential across nine tables. <strong>No entry carries the secret, a
   * prefix of it, a hash of it, or its length.</strong> It answers existence and timestamps, which
   * is what a rotation audit needs.
   *
   * <p>Read {@code rotatedAtBasis} and {@code lastUsedBasis} <em>before</em> acting on a null
   * timestamp: they say whether a null means "never" or "nothing records it". Treating the second
   * as the first is how a working integration gets rotated out of an abundance of caution.
   *
   * <p>A census, not a feed: nine heterogeneous sources with no common sort key, each capped, with
   * {@code truncated} on the envelope when a cap was hit. There is no cursor, so {@code truncated}
   * is the end of the road rather than an invitation to page.
   */
  public Map<String, Object> secrets() {
    String path = "/v1/workspace/secrets";
    return object(transport.get(path, Query.of()), path);
  }
}
