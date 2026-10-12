package com.chatlychat.chatly.errors;

/**
 * HTTP 404 — no such resource, <em>or</em> it exists in a different workspace.
 *
 * <p>A cross-workspace read is a 404 and not a 403 by design: Postgres row-level security scopes
 * every query to the key's workspace, so a row in someone else's tenant is indistinguishable from a
 * row that was never created. Do not read this as "deleted".
 */
public final class NotFoundException extends ChatlyException {
  private static final long serialVersionUID = 1L;

  public NotFoundException(
      String message, int status, String code, String requestId, String body, Throwable cause) {
    super(message, status, code, requestId, body, cause);
  }
}
