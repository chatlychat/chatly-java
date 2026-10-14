package com.chatlychat.chatly;

import static com.chatlychat.chatly.SurfaceIds.BOT;
import static com.chatlychat.chatly.SurfaceIds.CONTACT;
import static com.chatlychat.chatly.SurfaceIds.CONVERSATION;
import static com.chatlychat.chatly.SurfaceIds.TICKET;

import com.chatlychat.chatly.SurfaceCalls.Entry;
import java.util.List;
import java.util.Map;

/**
 * The inbox namespaces' later additions: {@code contacts}, {@code conversations},
 * {@code tickets}, {@code macros} and {@code reports}.
 *
 * <p>26 of the 104 routes added when this SDK's surface was completed. These namespaces already
 * existed — their first routes are in {@link SurfaceEntriesCore} — so a reader looking for
 * {@code conversations.list} will not find it here and a reader looking for
 * {@code conversations.linked} will.
 *
 * <p>No assertions live here; see {@link SurfaceEntriesCore} for why the entries are split across
 * four files at all.
 */
final class SurfaceEntriesInbox {

  static void add(List<Entry> e) {

    // ------------------------------------------------------ contacts
    e.add(new Entry("contacts", "cards", "POST", "/v1/contacts/{contactId}/cards",
        "/v1/contacts/" + CONTACT + "/cards",
        c -> c.contacts().cards(CONTACT)));
    e.add(new Entry("contacts", "mood", "GET", "/v1/contacts/{id}/mood",
        "/v1/contacts/" + CONTACT + "/mood",
        c -> c.contacts().mood(CONTACT)));

    // ------------------------------------------------- conversations
    e.add(new Entry("conversations", "linked", "GET", "/v1/conversations/{id}/linked",
        "/v1/conversations/" + CONVERSATION + "/linked",
        c -> c.conversations().linked(CONVERSATION)));
    e.add(new Entry("conversations", "outcomes", "GET", "/v1/conversations/{id}/outcomes",
        "/v1/conversations/" + CONVERSATION + "/outcomes",
        c -> c.conversations().outcomes(CONVERSATION)));
    e.add(new Entry("conversations", "record_outcome", "POST", "/v1/conversations/{id}/outcomes",
        "/v1/conversations/" + CONVERSATION + "/outcomes",
        c -> c.conversations().recordOutcome(CONVERSATION, "resolved")));
    e.add(new Entry("conversations", "return_to_bot", "POST", "/v1/conversations/{id}/return-to-bot",
        "/v1/conversations/" + CONVERSATION + "/return-to-bot",
        c -> c.conversations().returnToBot(CONVERSATION, BOT)));
    e.add(new Entry("conversations", "unread_counts", "GET", "/v1/conversations/unread-counts",
        "/v1/conversations/unread-counts",
        c -> c.conversations().unreadCounts()));
    e.add(new Entry("conversations", "update_blocks", "PATCH", "/v1/conversations/{id}/messages/{messageId}/blocks",
        "/v1/conversations/" + CONVERSATION + "/messages/msg_1/blocks",
        c -> c.conversations().updateBlocks(CONVERSATION, "msg_1",
            List.of(Map.of("id", "blk_1", "set", Map.of("text", "done"))))));

    // -------------------------------------------------------- macros
    e.add(new Entry("macros", "actions", "GET", "/v1/macros/actions",
        "/v1/macros/actions",
        c -> c.macros().actions()));
    e.add(new Entry("macros", "get", "GET", "/v1/macros/{id}",
        "/v1/macros/m_1",
        c -> c.macros().get("m_1")));
    e.add(new Entry("macros", "template", "GET", "/v1/macros/templates/{slug}",
        "/v1/macros/templates/refund-request",
        c -> c.macros().template("refund-request")));
    e.add(new Entry("macros", "templates", "GET", "/v1/macros/templates",
        "/v1/macros/templates",
        c -> c.macros().templates()));

    // ------------------------------------------------------- reports
    e.add(new Entry("reports", "attribution", "GET", "/v1/reports/attribution",
        "/v1/reports/attribution",
        c -> c.reports().attribution()));
    e.add(new Entry("reports", "cohorts", "GET", "/v1/reports/cohorts",
        "/v1/reports/cohorts",
        c -> c.reports().cohorts()));
    e.add(new Entry("reports", "labels", "GET", "/v1/reports/labels",
        "/v1/reports/labels",
        c -> c.reports().labels()));
    e.add(new Entry("reports", "response_times", "GET", "/v1/reports/response-times",
        "/v1/reports/response-times",
        c -> c.reports().responseTimes()));
    e.add(new Entry("reports", "sources", "GET", "/v1/reports/sources",
        "/v1/reports/sources",
        c -> c.reports().sources()));
    e.add(new Entry("reports", "teams", "GET", "/v1/reports/teams",
        "/v1/reports/teams",
        c -> c.reports().teams()));
    e.add(new Entry("reports", "theme_conversations", "GET", "/v1/reports/themes/{id}/conversations",
        "/v1/reports/themes/th_1/conversations",
        c -> c.reports().themeConversations("th_1")));
    e.add(new Entry("reports", "themes", "GET", "/v1/reports/themes",
        "/v1/reports/themes",
        c -> c.reports().themes()));
    e.add(new Entry("reports", "voice", "GET", "/v1/reports/voice",
        "/v1/reports/voice",
        c -> c.reports().voice()));

    // ------------------------------------------------------- tickets
    e.add(new Entry("tickets", "conversations", "GET", "/v1/tickets/{id}/conversations",
        "/v1/tickets/" + TICKET + "/conversations",
        c -> c.tickets().conversations(TICKET)));
    e.add(new Entry("tickets", "lineage", "GET", "/v1/tickets/{id}/lineage",
        "/v1/tickets/" + TICKET + "/lineage",
        c -> c.tickets().lineage(TICKET)));
    e.add(new Entry("tickets", "open_count", "GET", "/v1/tickets/open-count/{conversationId}",
        "/v1/tickets/open-count/" + CONVERSATION,
        c -> c.tickets().openCount(CONVERSATION)));
    e.add(new Entry("tickets", "status_history", "GET", "/v1/tickets/{id}/status-history",
        "/v1/tickets/" + TICKET + "/status-history",
        c -> c.tickets().statusHistory(TICKET)));
    e.add(new Entry("tickets", "time", "GET", "/v1/tickets/{id}/time",
        "/v1/tickets/" + TICKET + "/time",
        c -> c.tickets().time(TICKET)));
  }

  private SurfaceEntriesInbox() {}
}
