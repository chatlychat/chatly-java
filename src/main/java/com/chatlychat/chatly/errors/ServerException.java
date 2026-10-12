package com.chatlychat.chatly.errors;

/**
 * HTTP 5xx — the API failed. Already retried per the client's retry policy before it reached you,
 * so receiving one means every attempt failed. {@link ChatlyException#requestId()} is the thing to
 * quote when reporting it.
 */
public final class ServerException extends ChatlyException {
  private static final long serialVersionUID = 1L;

  public ServerException(
      String message, int status, String code, String requestId, String body, Throwable cause) {
    super(message, status, code, requestId, body, cause);
  }
}
