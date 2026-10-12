package com.chatlychat.chatly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The 55 surface methods, each paired with a throwaway invocation.
 *
 * <p>One list, used by two tests that ask different questions of it:
 *
 * <ul>
 *   <li>{@link SurfaceTest} — does each method issue the verb and URL the manifest declares?
 *   <li>{@link SurfaceLockTest} — executes every entry, records what came out, and writes
 *       {@code surface.lock.json} as evidence.
 * </ul>
 *
 * <p>Both run the client. Neither reads source text. That matters: the first version of
 * {@code scripts/check-sdk-surface-parity.mjs} matched path strings in SDK <em>source</em>, and so
 * counted a doc comment that merely mentioned a route as an implementation of it — this SDK was
 * credited 55/55 while two of those methods did not exist, because a Javadoc paragraph explaining
 * that two routes were broken contained their paths. Text matching cannot tell documentation from
 * code, and it cannot see a path assembled through a helper either. Executing the method can do
 * both.
 *
 * <p>The {@code ns} and {@code name} on each entry are the manifest's, so the lock this produces is
 * directly comparable to {@code packages/sdk-spec/surface.json}.
 */
final class SurfaceCalls {

  /** A page envelope, an object, and an array — enough to satisfy any method's decoder. */
  static final String PAGE = "{\"items\":[],\"nextCursor\":null}";

  static final String OBJECT = "{\"id\":\"x_1\"}";

  static final String ARRAY = "[{\"id\":\"x_1\"}]";

  /**
   * One surface method.
   *
   * @param ns the manifest's namespace, e.g. {@code contacts}
   * @param name the manifest's method name, e.g. {@code add_tags}
   * @param http the verb the manifest declares
   * @param pathTemplate the manifest's path, placeholders and all: {@code /v1/contacts/{id}/tags}
   * @param concretePath the path this invocation actually produces, with the arguments below
   *     substituted — the thing {@link SurfaceTest} compares against
   * @param response a body the method's decoder will accept
   * @param invoke calls the method with throwaway arguments
   */
  record Entry(
      String ns,
      String name,
      String http,
      String pathTemplate,
      String concretePath,
      String response,
      Consumer<Chatly> invoke) {

    /** {@code ns.name}, for an error message. */
    String id() {
      return ns + "." + name;
    }
  }

  /**
   * Argument values chosen so that no path segment needs percent-encoding.
   *
   * <p>Deliberate: the lock normalises a concrete path back to the manifest's template by splitting
   * on {@code /} and matching segments positionally, and an argument that encoded to {@code %2F}
   * would split into two segments and silently shift every later placeholder. Encoding itself is
   * tested in {@code ClientContractTest.pathSegmentsArePercentEncoded}.
   */
  private static final String CONTACT = "c_1";

  private static final String CONVERSATION = "cv_1";

  private static final String TICKET = "t_1";

  private static List<Entry> entries;

  static synchronized List<Entry> all() {
    if (entries == null) {
      entries = List.copyOf(build());
    }
    return entries;
  }

  private static List<Entry> build() {
    List<Entry> e = new ArrayList<>();

    e.add(new Entry("root", "whoami", "GET", "/v1/api-keys/whoami", "/v1/api-keys/whoami",
        OBJECT, c -> c.whoami()));

    // ------------------------------------------------------------- contacts
    e.add(new Entry("contacts", "list", "GET", "/v1/contacts", "/v1/contacts",
        PAGE, c -> c.contacts().list()));
    e.add(new Entry("contacts", "get", "GET", "/v1/contacts/{id}", "/v1/contacts/" + CONTACT,
        OBJECT, c -> c.contacts().get(CONTACT)));
    e.add(new Entry("contacts", "upsert", "POST", "/v1/contacts", "/v1/contacts",
        OBJECT, c -> c.contacts().upsert(Map.of("externalId", "u-1"))));
    e.add(new Entry("contacts", "bulk_upsert", "POST", "/v1/contacts/bulk", "/v1/contacts/bulk",
        OBJECT, c -> c.contacts().bulkUpsert(List.of(Map.of("externalId", "u-1")))));
    e.add(new Entry("contacts", "merge", "POST", "/v1/contacts/{id}/merge",
        "/v1/contacts/" + CONTACT + "/merge", OBJECT, c -> c.contacts().merge(CONTACT, "c_2")));
    e.add(new Entry("contacts", "add_tags", "POST", "/v1/contacts/{id}/tags",
        "/v1/contacts/" + CONTACT + "/tags", OBJECT, c -> c.contacts().addTags(CONTACT, "vip")));
    e.add(new Entry("contacts", "remove_tag", "DELETE", "/v1/contacts/{id}/tags/{tag}",
        "/v1/contacts/" + CONTACT + "/tags/vip", OBJECT,
        c -> c.contacts().removeTag(CONTACT, "vip")));
    e.add(new Entry("contacts", "unsubscribe", "POST", "/v1/contacts/{id}/unsubscribe",
        "/v1/contacts/" + CONTACT + "/unsubscribe", OBJECT,
        c -> c.contacts().unsubscribe(CONTACT, true)));
    e.add(new Entry("contacts", "journey", "GET", "/v1/contacts/{id}/journey",
        "/v1/contacts/" + CONTACT + "/journey", PAGE, c -> c.contacts().journey(CONTACT)));

    // -------------------------------------------------------- conversations
    e.add(new Entry("conversations", "list", "GET", "/v1/conversations", "/v1/conversations",
        PAGE, c -> c.conversations().list()));
    e.add(new Entry("conversations", "get", "GET", "/v1/conversations/{id}",
        "/v1/conversations/" + CONVERSATION, OBJECT, c -> c.conversations().get(CONVERSATION)));
    e.add(new Entry("conversations", "messages", "GET", "/v1/conversations/{id}/messages",
        "/v1/conversations/" + CONVERSATION + "/messages", PAGE,
        c -> c.conversations().messages(CONVERSATION)));
    e.add(new Entry("conversations", "send", "POST", "/v1/conversations/{id}/messages",
        "/v1/conversations/" + CONVERSATION + "/messages", OBJECT,
        c -> c.conversations().send(CONVERSATION, "hello")));
    e.add(new Entry("conversations", "unattended", "GET", "/v1/conversations/unattended",
        "/v1/conversations/unattended", PAGE, c -> c.conversations().unattended()));
    e.add(new Entry("conversations", "agent_handling", "GET",
        "/v1/conversations/{id}/agent-handling",
        "/v1/conversations/" + CONVERSATION + "/agent-handling", OBJECT,
        c -> c.conversations().agentHandling(CONVERSATION)));
    e.add(new Entry("conversations", "set_agent_handling", "PUT",
        "/v1/conversations/{id}/agent-handling",
        "/v1/conversations/" + CONVERSATION + "/agent-handling", OBJECT,
        c -> c.conversations().setAgentHandling(CONVERSATION, "agent")));
    e.add(new Entry("conversations", "generated_replies", "GET",
        "/v1/conversations/{id}/generated-replies",
        "/v1/conversations/" + CONVERSATION + "/generated-replies", ARRAY,
        c -> c.conversations().generatedReplies(CONVERSATION)));
    e.add(new Entry("conversations", "generate_reply", "POST",
        "/v1/conversations/{id}/generated-replies",
        "/v1/conversations/" + CONVERSATION + "/generated-replies", OBJECT,
        c -> c.conversations().generateReply(CONVERSATION, "draft")));
    e.add(new Entry("conversations", "scheduled_messages", "GET",
        "/v1/conversations/{id}/scheduled-messages",
        "/v1/conversations/" + CONVERSATION + "/scheduled-messages", ARRAY,
        c -> c.conversations().scheduledMessages(CONVERSATION)));
    e.add(new Entry("conversations", "schedule_message", "POST",
        "/v1/conversations/{id}/scheduled-messages",
        "/v1/conversations/" + CONVERSATION + "/scheduled-messages", OBJECT,
        c -> c.conversations().scheduleMessage(CONVERSATION, "later", "2026-10-13T09:00:00Z")));
    e.add(new Entry("conversations", "cancel_scheduled", "DELETE",
        "/v1/conversations/scheduled-messages/{scheduled_id}",
        "/v1/conversations/scheduled-messages/sm_1", OBJECT,
        c -> c.conversations().cancelScheduled("sm_1")));

    // -------------------------------------------------------------- tickets
    e.add(new Entry("tickets", "list", "GET", "/v1/tickets", "/v1/tickets",
        PAGE, c -> c.tickets().list()));
    e.add(new Entry("tickets", "comments", "GET", "/v1/tickets/{id}/comments",
        "/v1/tickets/" + TICKET + "/comments", ARRAY, c -> c.tickets().comments(TICKET)));
    e.add(new Entry("tickets", "for_conversation", "GET",
        "/v1/tickets/for-conversation/{conversation_id}",
        "/v1/tickets/for-conversation/" + CONVERSATION, ARRAY,
        c -> c.tickets().forConversation(CONVERSATION)));
    e.add(new Entry("tickets", "queues", "GET", "/v1/tickets/queues", "/v1/tickets/queues",
        ARRAY, c -> c.tickets().queues()));
    e.add(new Entry("tickets", "merge", "POST", "/v1/tickets/{id}/merge",
        "/v1/tickets/" + TICKET + "/merge", OBJECT, c -> c.tickets().merge(TICKET, "t_2")));
    e.add(new Entry("tickets", "split", "POST", "/v1/tickets/{id}/split",
        "/v1/tickets/" + TICKET + "/split", OBJECT, c -> c.tickets().split(TICKET, "a title")));
    e.add(new Entry("tickets", "bulk", "POST", "/v1/tickets/bulk", "/v1/tickets/bulk",
        OBJECT, c -> c.tickets().bulk(List.of(TICKET), "close")));

    // --------------------------------------------------------------- macros
    e.add(new Entry("macros", "list", "GET", "/v1/macros", "/v1/macros",
        ARRAY, c -> c.macros().list()));
    e.add(new Entry("macros", "create", "POST", "/v1/macros", "/v1/macros", OBJECT,
        c -> c.macros().create(Map.of("name", "n", "scope", "workspace", "body", "b"))));
    e.add(new Entry("macros", "update", "PATCH", "/v1/macros/{id}", "/v1/macros/m_1", OBJECT,
        c -> c.macros().update("m_1", Map.of("name", "n2"))));
    e.add(new Entry("macros", "delete", "DELETE", "/v1/macros/{id}", "/v1/macros/m_1", null,
        c -> c.macros().delete("m_1")));

    // ------------------------------------------------------------------- kb
    e.add(new Entry("kb", "articles", "GET", "/v1/kb/articles", "/v1/kb/articles",
        PAGE, c -> c.kb().articles()));
    e.add(new Entry("kb", "article", "GET", "/v1/kb/articles/{slug}",
        "/v1/kb/articles/how-to-refund", OBJECT, c -> c.kb().article("how-to-refund")));
    e.add(new Entry("kb", "collections", "GET", "/v1/kb/collections", "/v1/kb/collections",
        ARRAY, c -> c.kb().collections()));

    // --------------------------------------------------------------- search
    e.add(new Entry("search", "query", "GET", "/v1/search", "/v1/search",
        PAGE, c -> c.search().query("refund")));

    // --------------------------------------------------------------- events
    e.add(new Entry("events", "track", "POST", "/v1/cdp/events", "/v1/cdp/events",
        OBJECT, c -> c.events().track(CONTACT, "did.thing")));
    e.add(new Entry("events", "list", "GET", "/v1/cdp/events", "/v1/cdp/events",
        PAGE, c -> c.events().list()));

    // ------------------------------------------------------------- webhooks
    e.add(new Entry("webhooks", "deliveries", "GET", "/v1/webhooks/deliveries",
        "/v1/webhooks/deliveries", PAGE, c -> c.webhooks().deliveries()));
    e.add(new Entry("webhooks", "delivery", "GET", "/v1/webhooks/deliveries/{id}",
        "/v1/webhooks/deliveries/d_1", OBJECT, c -> c.webhooks().delivery("d_1")));
    e.add(new Entry("webhooks", "redeliver", "POST", "/v1/webhooks/deliveries/{id}/redeliver",
        "/v1/webhooks/deliveries/d_1/redeliver", OBJECT, c -> c.webhooks().redeliver("d_1")));

    // -------------------------------------------------------------- reports
    e.add(new Entry("reports", "overview", "GET", "/v1/reports/overview", "/v1/reports/overview",
        OBJECT, c -> c.reports().overview()));
    e.add(new Entry("reports", "agents", "GET", "/v1/reports/agents", "/v1/reports/agents",
        ARRAY, c -> c.reports().agents()));
    e.add(new Entry("reports", "conversations_by_day", "GET",
        "/v1/reports/conversations-by-day", "/v1/reports/conversations-by-day", ARRAY,
        c -> c.reports().conversationsByDay()));
    e.add(new Entry("reports", "sla", "GET", "/v1/reports/sla", "/v1/reports/sla",
        OBJECT, c -> c.reports().sla()));

    // ------------------------------------------------------------- channels
    e.add(new Entry("channels", "list", "GET", "/v1/channels", "/v1/channels",
        ARRAY, c -> c.channels().list()));
    e.add(new Entry("channels", "get", "GET", "/v1/channels/{id}", "/v1/channels/ch_1",
        OBJECT, c -> c.channels().get("ch_1")));

    // -------------------------------------------------------------- billing
    e.add(new Entry("billing", "plans", "GET", "/v1/billing/plans", "/v1/billing/plans",
        ARRAY, c -> c.billing().plans()));
    e.add(new Entry("billing", "usage", "GET", "/v1/billing/usage", "/v1/billing/usage",
        OBJECT, c -> c.billing().usage("ai_tokens")));

    // ------------------------------------------------------------ workspace
    e.add(new Entry("workspace", "settings", "GET", "/v1/workspace/settings",
        "/v1/workspace/settings", OBJECT, c -> c.workspace().settings()));
    e.add(new Entry("workspace", "labels", "GET", "/v1/labels", "/v1/labels",
        ARRAY, c -> c.workspace().labels()));
    e.add(new Entry("workspace", "inboxes", "GET", "/v1/inboxes", "/v1/inboxes",
        ARRAY, c -> c.workspace().inboxes()));
    e.add(new Entry("workspace", "team_members", "GET", "/v1/teams/{id}/members",
        "/v1/teams/tm_1/members", ARRAY, c -> c.workspace().teamMembers("tm_1")));
    e.add(new Entry("workspace", "agent_skills", "GET", "/v1/agents/skills",
        "/v1/agents/skills", "{\"skills\":[\"billing\"]}", c -> c.workspace().agentSkills()));

    return e;
  }

  /** One request, as the transport saw it, with the base URL and query string stripped. */
  record Issued(String method, String path) {}

  /**
   * Execute one entry against a scripted transport and report the single request it made.
   *
   * @throws AssertionError if the method made zero or more than one request — a surface method is
   *     one route, and a method that quietly makes two is not something a lock can describe
   */
  static Issued execute(Entry entry) {
    FakeSender sender = new FakeSender();
    sender.then(entry.response() == null ? 204 : 200, entry.response());
    entry.invoke().accept(sender.clientNoRetries());

    if (sender.callCount() != 1) {
      throw new AssertionError(
          entry.id() + " made " + sender.callCount() + " requests; a surface method must make 1");
    }
    Transport.Request request = sender.lastRequest();
    String url = request.url();
    if (!url.startsWith(Chatly.DEFAULT_BASE_URL)) {
      throw new AssertionError(entry.id() + " did not call the default base URL: " + url);
    }
    String path = url.substring(Chatly.DEFAULT_BASE_URL.length());
    int queryStart = path.indexOf('?');
    if (queryStart >= 0) {
      path = path.substring(0, queryStart);
    }
    return new Issued(request.method(), path);
  }

  /**
   * Rewrite a concrete path back to the manifest's template form.
   *
   * <p>{@code /v1/contacts/c_1/tags/vip} against the template
   * {@code /v1/contacts/{id}/tags/{tag}} yields the template's own text. Matching is positional,
   * segment by segment: wherever the template has a placeholder, the placeholder is taken, and
   * wherever it has a literal, the actual segment must equal it. A literal that does not match is an
   * error rather than a silent substitution, because that is precisely the typo this is meant to
   * catch — {@code /v1/tickets/queue} against {@code /v1/tickets/queues} must not pass.
   */
  static String normalise(String actualPath, String template, String entryId) {
    String[] actual = actualPath.split("/", -1);
    String[] parts = template.split("/", -1);
    if (actual.length != parts.length) {
      throw new AssertionError(
          entryId
              + ": issued "
              + actualPath
              + " which has "
              + actual.length
              + " segments, but the manifest template "
              + template
              + " has "
              + parts.length);
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        out.append('/');
      }
      String part = parts[i];
      if (part.startsWith("{") && part.endsWith("}")) {
        if (actual[i].isEmpty()) {
          throw new AssertionError(
              entryId + ": issued an empty segment where " + part + " was expected: " + actualPath);
        }
        out.append(part);
      } else {
        if (!part.equals(actual[i])) {
          throw new AssertionError(
              entryId
                  + ": issued "
                  + actualPath
                  + " but the manifest declares "
                  + template
                  + " (segment "
                  + i
                  + ": got \""
                  + actual[i]
                  + "\", expected \""
                  + part
                  + "\")");
        }
        out.append(part);
      }
    }
    return out.toString();
  }

  private SurfaceCalls() {}
}
