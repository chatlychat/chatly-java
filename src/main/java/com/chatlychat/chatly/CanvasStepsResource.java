package com.chatlychat.chatly;

import java.util.Map;

/**
 * Canvas steps — one turn of an interactive card inside a conversation.
 *
 * <p>Reached as {@code chatly.canvasSteps()}.
 *
 * <p>A canvas is a small form or card an integration renders into the widget; when a visitor acts
 * on it, Chatly asks your service what to show next and the question is identified by a step id.
 * These two methods are the server-to-server half of that handshake.
 */
public final class CanvasStepsResource extends Resource {

  private static final String COLLECTION = "/v1/canvas-steps";

  CanvasStepsResource(Transport transport) {
    super(transport);
  }

  /**
   * Is this step still pending, resolved, or expired? {@code GET /v1/canvas-steps/{id}}
   *
   * <p>The question an integrator has to answer before deciding whether to refund a charge they
   * took for a step they never managed to resolve. {@code messages:read}.
   *
   * <p>It carries <strong>no canvas</strong>: the step the visitor is looking at is readable from
   * the conversation, and duplicating it here would be a second copy that can disagree.
   */
  public Map<String, Object> get(String stepId) {
    String path = COLLECTION + "/" + seg(stepId, "stepId");
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Answer a pending step with the next one. {@code POST /v1/canvas-steps/{id}/resolve}
   *
   * <p>Replaces the progress line the visitor is watching with the canvas you return, and publishes
   * {@code message.updated} so an open widget updates with no reload. {@code messages:write}.
   *
   * <p><strong>The conversation and the message come from the step id, never from your request</strong>
   * — there is nothing to address wrongly, and nothing you send can retarget it.
   *
   * <p>Two refusals worth planning for. A 409 means the step was already resolved or has expired:
   * exactly one resolve wins, because the claiming UPDATE carries {@code status = 'pending'} in its
   * WHERE, so a retry after a timeout is answered correctly whether or not you sent an idempotency
   * key. And a 404 includes the case where you resolve from <em>inside</em> the request that
   * answered {@code {pending: true}} — the row is written after we answer the visitor, so your
   * handler must return first and resolve afterwards.
   *
   * @param canvas the next canvas to show, as the widget canvas schema defines it. The body is
   *     {@code {"canvas": …}} and strict: an unexpected key is a 400, not an ignored field.
   */
  public Map<String, Object> resolve(String stepId, Map<String, Object> canvas) {
    String path = COLLECTION + "/" + seg(stepId, "stepId") + "/resolve";
    return object(
        transport.post(path, Query.of(), Map.of("canvas", required(canvas, "canvas"))), path);
  }
}
