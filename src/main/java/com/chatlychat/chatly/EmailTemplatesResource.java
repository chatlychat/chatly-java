package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Transactional email templates — the five system emails a workspace may override.
 *
 * <p>Reached as {@code chatly.emailTemplates()}.
 *
 * <p>The five kinds are {@code workspace_invite}, {@code trial_ending_reminder},
 * {@code conversation_cap_warning}, {@code hired_agent_engagement_invite} and
 * {@code hired_agent_engagement_started}. The kind goes in the path and is validated against that
 * list at the boundary, so an unknown one is a 400 naming it rather than a 404 — which is the right
 * answer, and the reason this SDK does not keep its own copy of the enum to check against: a
 * hardcoded list in a client goes stale the first time a sixth email is added.
 *
 * <p>This is a <strong>catalogue, not a table</strong>: all five kinds always exist, and
 * "overriding" one stores your copy beside the built-in rather than replacing a row.
 */
public final class EmailTemplatesResource extends Resource {

  private static final String COLLECTION = "/v1/email-templates";

  EmailTemplatesResource(Transport transport) {
    super(transport);
  }

  /**
   * All five kinds, with this workspace's override if any. {@code GET /v1/email-templates}
   *
   * <p>A bare array of exactly five rows — the catalogue. {@code override} is {@code null} where
   * the workspace is still on the built-in copy, so a null is "not customised" and not "missing
   * data".
   */
  public List<Map<String, Object>> list() {
    return array(transport.get(COLLECTION, Query.of()), COLLECTION);
  }

  /**
   * Render unsaved text through the real send-time renderer.
   *
   * <p>{@code POST /v1/email-templates/preview}
   *
   * <p>A POST because the subject and body are the request — far too long for a query string, and a
   * preview of someone's draft copy has no business in an access log. It writes nothing; the 201 is
   * only Nest's default for a POST with no {@code @HttpCode}.
   *
   * <p>The response carries the rendered subject, HTML and text, <strong>plus any token you wrote
   * that this kind does not supply</strong>. That last list is the reason to call it: a token the
   * kind does not provide renders as nothing in the real email, silently, and this is the only
   * place that tells you.
   *
   * @param kind one of the five
   * @param subject may be empty here, unlike {@link #update}
   * @param body may be empty here, unlike {@link #update}
   */
  public Map<String, Object> preview(String kind, String subject, String body) {
    String path = COLLECTION + "/preview";
    Map<String, Object> payload = body();
    payload.put("kind", required(kind, "kind"));
    payload.put("subject", required(subject, "subject"));
    payload.put("body", required(body, "body"));
    return object(transport.post(path, Query.of(), payload), path);
  }

  /**
   * Replace this workspace's copy of one email. {@code PUT /v1/email-templates/{kind}}
   *
   * <p>Both fields are required and both are trimmed and must be non-empty — there is no partial
   * update, because a subject with no body is not a sendable email. Returns the kind as stored,
   * the same shape as one element of {@link #list()}.
   *
   * <p>Preview it first: a token this kind does not supply is accepted here without complaint and
   * renders as nothing in the delivered mail.
   */
  public Map<String, Object> update(String kind, String subject, String bodyText) {
    String path = COLLECTION + "/" + seg(kind, "kind");
    Map<String, Object> payload = body();
    payload.put("subject", required(subject, "subject"));
    payload.put("body", required(bodyText, "bodyText"));
    return object(transport.put(path, Query.of(), payload), path);
  }

  /**
   * Restore the built-in copy. {@code DELETE /v1/email-templates/{kind}}
   *
   * <p>It deletes the override rather than copying today's default into it — so the workspace
   * tracks the built-in from then on, including future changes to it.
   *
   * <p><strong>It has a body, not a 204.</strong> {@code {"restored": false}} with a 200 means the
   * workspace was already on the built-in: the call is idempotent, and that flag is the only way to
   * tell "I just removed your override" from "there was nothing to remove". It does not return the
   * restored template — read {@link #list()} for that.
   */
  public Map<String, Object> delete(String kind) {
    String path = COLLECTION + "/" + seg(kind, "kind");
    return object(transport.delete(path, Query.of(), null), path);
  }
}
