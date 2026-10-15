package com.chatlychat.chatly;

import static com.chatlychat.chatly.SurfaceIds.CONTACT;
import static com.chatlychat.chatly.SurfaceIds.CONVERSATION;
import static com.chatlychat.chatly.SurfaceIds.TICKET;

import com.chatlychat.chatly.SurfaceCalls.Entry;
import java.util.List;
import java.util.Map;

/**
 * The root routes and the namespaces this SDK carried before its surface was completed — 56
 * entries, as invocable ones.
 *
 * <p>One of four data classes behind {@link SurfaceCalls#all()}. They are separate files for a
 * mechanical reason — CONTRACT &sect;9 caps a file at 650 lines and 163 entries plus the harness is
 * well past it — and split along namespace lines rather than arbitrarily, so "where is
 * {@code bots.deliveries} declared?" has an answer you can guess.
 *
 * <p><strong>This file holds no assertions.</strong> {@link SurfaceTest} and
 * {@link SurfaceLockTest} ask different questions of the same list; a data class that started
 * asserting things would make a third place to look when a route is wrong.
 */
final class SurfaceEntriesCore {

  static void add(List<Entry> e) {

    e.add(new Entry("root", "whoami", "GET", "/v1/api-keys/whoami", "/v1/api-keys/whoami",
         c -> c.whoami()));
    // Added 2026-10-14, with webhooks.delete and workspace.mine, when three
    // routes that were already key-accessible reached the shared manifest.
    e.add(new Entry("root", "me", "GET", "/v1/me", "/v1/me",
         c -> c.me()));

    // ------------------------------------------------------------- contacts
    e.add(new Entry("contacts", "list", "GET", "/v1/contacts", "/v1/contacts",
         c -> c.contacts().list()));
    e.add(new Entry("contacts", "get", "GET", "/v1/contacts/{id}", "/v1/contacts/" + CONTACT,
         c -> c.contacts().get(CONTACT)));
    e.add(new Entry("contacts", "upsert", "POST", "/v1/contacts", "/v1/contacts",
         c -> c.contacts().upsert(Map.of("externalId", "u-1"))));
    e.add(new Entry("contacts", "bulk_upsert", "POST", "/v1/contacts/bulk", "/v1/contacts/bulk",
         c -> c.contacts().bulkUpsert(List.of(Map.of("externalId", "u-1")))));
    e.add(new Entry("contacts", "merge", "POST", "/v1/contacts/{id}/merge",
        "/v1/contacts/" + CONTACT + "/merge",  c -> c.contacts().merge(CONTACT, "c_2")));
    e.add(new Entry("contacts", "add_tags", "POST", "/v1/contacts/{id}/tags",
        "/v1/contacts/" + CONTACT + "/tags",  c -> c.contacts().addTags(CONTACT, "vip")));
    e.add(new Entry("contacts", "remove_tag", "DELETE", "/v1/contacts/{id}/tags/{tag}",
        "/v1/contacts/" + CONTACT + "/tags/vip",
        c -> c.contacts().removeTag(CONTACT, "vip")));
    e.add(new Entry("contacts", "unsubscribe", "POST", "/v1/contacts/{id}/unsubscribe",
        "/v1/contacts/" + CONTACT + "/unsubscribe",
        c -> c.contacts().unsubscribe(CONTACT, true)));
    e.add(new Entry("contacts", "journey", "GET", "/v1/contacts/{id}/journey",
        "/v1/contacts/" + CONTACT + "/journey",  c -> c.contacts().journey(CONTACT)));

    // -------------------------------------------------------- conversations
    e.add(new Entry("conversations", "list", "GET", "/v1/conversations", "/v1/conversations",
         c -> c.conversations().list()));
    e.add(new Entry("conversations", "get", "GET", "/v1/conversations/{id}",
        "/v1/conversations/" + CONVERSATION,  c -> c.conversations().get(CONVERSATION)));
    e.add(new Entry("conversations", "messages", "GET", "/v1/conversations/{id}/messages",
        "/v1/conversations/" + CONVERSATION + "/messages",
        c -> c.conversations().messages(CONVERSATION)));
    e.add(new Entry("conversations", "send", "POST", "/v1/conversations/{id}/messages",
        "/v1/conversations/" + CONVERSATION + "/messages",
        c -> c.conversations().send(CONVERSATION, "hello")));
    e.add(new Entry("conversations", "unattended", "GET", "/v1/conversations/unattended",
        "/v1/conversations/unattended",  c -> c.conversations().unattended()));
    e.add(new Entry("conversations", "agent_handling", "GET",
        "/v1/conversations/{id}/agent-handling",
        "/v1/conversations/" + CONVERSATION + "/agent-handling",
        c -> c.conversations().agentHandling(CONVERSATION)));
    e.add(new Entry("conversations", "set_agent_handling", "PUT",
        "/v1/conversations/{id}/agent-handling",
        "/v1/conversations/" + CONVERSATION + "/agent-handling",
        c -> c.conversations().setAgentHandling(CONVERSATION, "agent")));
    e.add(new Entry("conversations", "generated_replies", "GET",
        "/v1/conversations/{id}/generated-replies",
        "/v1/conversations/" + CONVERSATION + "/generated-replies",
        c -> c.conversations().generatedReplies(CONVERSATION)));
    e.add(new Entry("conversations", "generate_reply", "POST",
        "/v1/conversations/{id}/generated-replies",
        "/v1/conversations/" + CONVERSATION + "/generated-replies",
        c -> c.conversations().generateReply(CONVERSATION, "draft")));
    e.add(new Entry("conversations", "scheduled_messages", "GET",
        "/v1/conversations/{id}/scheduled-messages",
        "/v1/conversations/" + CONVERSATION + "/scheduled-messages",
        c -> c.conversations().scheduledMessages(CONVERSATION)));
    e.add(new Entry("conversations", "schedule_message", "POST",
        "/v1/conversations/{id}/scheduled-messages",
        "/v1/conversations/" + CONVERSATION + "/scheduled-messages",
        c -> c.conversations().scheduleMessage(CONVERSATION, "later", "2026-10-13T09:00:00Z")));
    e.add(new Entry("conversations", "cancel_scheduled", "DELETE",
        "/v1/conversations/scheduled-messages/{scheduled_id}",
        "/v1/conversations/scheduled-messages/sm_1",
        c -> c.conversations().cancelScheduled("sm_1")));

    // -------------------------------------------------------------- tickets
    e.add(new Entry("tickets", "list", "GET", "/v1/tickets", "/v1/tickets",
         c -> c.tickets().list()));
    e.add(new Entry("tickets", "comments", "GET", "/v1/tickets/{id}/comments",
        "/v1/tickets/" + TICKET + "/comments",  c -> c.tickets().comments(TICKET)));
    e.add(new Entry("tickets", "for_conversation", "GET",
        "/v1/tickets/for-conversation/{conversation_id}",
        "/v1/tickets/for-conversation/" + CONVERSATION,
        c -> c.tickets().forConversation(CONVERSATION)));
    e.add(new Entry("tickets", "queues", "GET", "/v1/tickets/queues", "/v1/tickets/queues",
         c -> c.tickets().queues()));
    e.add(new Entry("tickets", "merge", "POST", "/v1/tickets/{id}/merge",
        "/v1/tickets/" + TICKET + "/merge",  c -> c.tickets().merge(TICKET, "t_2")));
    e.add(new Entry("tickets", "split", "POST", "/v1/tickets/{id}/split",
        "/v1/tickets/" + TICKET + "/split",  c -> c.tickets().split(TICKET, "a title")));
    e.add(new Entry("tickets", "bulk", "POST", "/v1/tickets/bulk", "/v1/tickets/bulk",
         c -> c.tickets().bulk(List.of(TICKET), "close")));

    // --------------------------------------------------------------- macros
    e.add(new Entry("macros", "list", "GET", "/v1/macros", "/v1/macros",
         c -> c.macros().list()));
    e.add(new Entry("macros", "create", "POST", "/v1/macros", "/v1/macros",
        c -> c.macros().create(Map.of("name", "n", "scope", "workspace", "body", "b"))));
    e.add(new Entry("macros", "update", "PATCH", "/v1/macros/{id}", "/v1/macros/m_1",
        c -> c.macros().update("m_1", Map.of("name", "n2"))));
    e.add(new Entry("macros", "delete", "DELETE", "/v1/macros/{id}", "/v1/macros/m_1",
        c -> {
          // The one void method on the surface. `null` is what a 204 decodes to,
          // and ResponseShape.actual maps it to `empty` — so the shape assertion
          // still has something to look at rather than quietly skipping it.
          c.macros().delete("m_1");
          return null;
        }));

    // ------------------------------------------------------------------- kb
    e.add(new Entry("kb", "articles", "GET", "/v1/kb/articles", "/v1/kb/articles",
         c -> c.kb().articles()));
    e.add(new Entry("kb", "article", "GET", "/v1/kb/articles/{slug}",
        "/v1/kb/articles/how-to-refund",  c -> c.kb().article("how-to-refund")));
    e.add(new Entry("kb", "collections", "GET", "/v1/kb/collections", "/v1/kb/collections",
         c -> c.kb().collections()));

    // --------------------------------------------------------------- search
    e.add(new Entry("search", "query", "GET", "/v1/search", "/v1/search",
         c -> c.search().query("refund")));

    // --------------------------------------------------------------- events
    e.add(new Entry("events", "track", "POST", "/v1/cdp/events", "/v1/cdp/events",
         c -> c.events().track(CONTACT, "did.thing")));
    e.add(new Entry("events", "list", "GET", "/v1/cdp/events", "/v1/cdp/events",
         c -> c.events().list()));

    // ------------------------------------------------------------- webhooks
    e.add(new Entry("webhooks", "deliveries", "GET", "/v1/webhooks/deliveries",
        "/v1/webhooks/deliveries",  c -> c.webhooks().deliveries()));
    e.add(new Entry("webhooks", "delivery", "GET", "/v1/webhooks/deliveries/{id}",
        "/v1/webhooks/deliveries/d_1",  c -> c.webhooks().delivery("d_1")));
    e.add(new Entry("webhooks", "redeliver", "POST", "/v1/webhooks/deliveries/{id}/redeliver",
        "/v1/webhooks/deliveries/d_1/redeliver",  c -> c.webhooks().redeliver("d_1")));

    // -------------------------------------------------------------- reports
    e.add(new Entry("reports", "overview", "GET", "/v1/reports/overview", "/v1/reports/overview",
         c -> c.reports().overview()));
    e.add(new Entry("reports", "agents", "GET", "/v1/reports/agents", "/v1/reports/agents",
         c -> c.reports().agents()));
    e.add(new Entry("reports", "conversations_by_day", "GET",
        "/v1/reports/conversations-by-day", "/v1/reports/conversations-by-day",
        c -> c.reports().conversationsByDay()));
    e.add(new Entry("reports", "sla", "GET", "/v1/reports/sla", "/v1/reports/sla",
         c -> c.reports().sla()));

    // ------------------------------------------------------------- channels
    e.add(new Entry("channels", "list", "GET", "/v1/channels", "/v1/channels",
         c -> c.channels().list()));
    e.add(new Entry("channels", "get", "GET", "/v1/channels/{id}", "/v1/channels/ch_1",
         c -> c.channels().get("ch_1")));

    // -------------------------------------------------------------- billing
    e.add(new Entry("billing", "plans", "GET", "/v1/billing/plans", "/v1/billing/plans",
         c -> c.billing().plans()));
    e.add(new Entry("billing", "usage", "GET", "/v1/billing/usage", "/v1/billing/usage",
         c -> c.billing().usage("ai_tokens")));

    // ------------------------------------------------------------ workspace
    e.add(new Entry("workspace", "settings", "GET", "/v1/workspace/settings",
        "/v1/workspace/settings",  c -> c.workspace().settings()));
    e.add(new Entry("workspace", "labels", "GET", "/v1/labels", "/v1/labels",
         c -> c.workspace().labels()));
    e.add(new Entry("workspace", "inboxes", "GET", "/v1/inboxes", "/v1/inboxes",
         c -> c.workspace().inboxes()));
    e.add(new Entry("workspace", "team_members", "GET", "/v1/teams/{id}/members",
        "/v1/teams/tm_1/members",  c -> c.workspace().teamMembers("tm_1")));
    e.add(new Entry("workspace", "agent_skills", "GET", "/v1/agents/skills",
        "/v1/agents/skills", c -> c.workspace().agentSkills()));

  }

  private SurfaceEntriesCore() {}
}
