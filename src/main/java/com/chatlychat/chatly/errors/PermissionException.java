package com.chatlychat.chatly.errors;

/**
 * HTTP 403 — the key is valid but lacks the scope for this route, or the route does not accept an
 * API key at all.
 *
 * <p>The second case is worth knowing about: 140 of the API's 886 routes carry {@code @AllowApiKey()}
 * and the rest answer a {@code ck_} key with 403 because they need a signed-in person. Every typed
 * method in this SDK is on the accessible list; if you reach a 403 through
 * {@link com.chatlychat.chatly.Chatly#request}, check that first, then check
 * {@code chatly.whoami().get("scopes")}.
 */
public final class PermissionException extends ChatlyException {
  private static final long serialVersionUID = 1L;

  public PermissionException(
      String message, int status, String code, String requestId, String body, Throwable cause) {
    super(message, status, code, requestId, body, cause);
  }
}
