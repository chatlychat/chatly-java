package com.chatlychat.chatly.errors;

/**
 * HTTP 401 — the API key was missing, malformed, revoked, or belongs to a deleted workspace.
 *
 * <p>Distinct from {@link PermissionException} on purpose: this one means "your credential is not
 * valid", that one means "your credential is valid but may not do this". The contract requires a
 * caller be able to tell those apart without parsing a message string, because the fixes are
 * completely different — mint a new key versus widen the scopes on the existing one.
 */
public final class AuthenticationException extends ChatlyException {
  private static final long serialVersionUID = 1L;

  public AuthenticationException(
      String message, int status, String code, String requestId, String body, Throwable cause) {
    super(message, status, code, requestId, body, cause);
  }
}
