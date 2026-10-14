package com.chatlychat.chatly;

/**
 * The throwaway argument values the surface entries are invoked with.
 *
 * <p>Shared by the four {@code SurfaceEntries*} data classes so that one route's id and another's
 * are visibly the same value where they should be — {@code tickets.open_count} takes a
 * <em>conversation</em> id, and a reader comparing it against {@code conversations.get} should be
 * able to see that at a glance.
 *
 * <h2>Why these particular strings</h2>
 *
 * <p>Every one of them is already URL-safe. That is deliberate and not cosmetic: the surface lock
 * normalises a concrete path back to the manifest's template by splitting on {@code /} and matching
 * segments positionally, so an argument that percent-encoded to {@code %2F} would split into two
 * segments and silently shift every later placeholder — a lock row that looks right and describes a
 * different route. Encoding itself is tested separately, in
 * {@code ClientContractTest.pathSegmentsArePercentEncoded}.
 *
 * <p>They are also distinguishable from each other. An earlier version of this suite used
 * {@code "x"} for everything, which meant a method that swapped two of its own arguments produced
 * exactly the path the manifest declares.
 */
final class SurfaceIds {

  static final String CONTACT = "c_1";
  static final String CONVERSATION = "cv_1";
  static final String TICKET = "t_1";
  static final String BOT = "b_1";

  /**
   * An email-template kind, which is an enum member rather than an id.
   *
   * <p>{@code workspace_invite} is one of the five the server accepts. A made-up kind would be a
   * 400 at the boundary — the kind is validated in the path — and while this suite's transport
   * never reaches a server, using a real member keeps the fixture readable as documentation.
   */
  static final String KIND = "workspace_invite";

  /**
   * A style guide long enough to clear {@code POST /v1/ai/tone/generate}'s 40-character floor.
   *
   * <p>The SDK checks that bound locally, so a shorter string here would throw before the request
   * was built and the entry would fail with an {@code IllegalArgumentException} rather than
   * exercising the route.
   */
  static final String STYLE_GUIDE =
      "Write plainly. Prefer short sentences. Never apologise twice in one reply.";

  private SurfaceIds() {}
}
