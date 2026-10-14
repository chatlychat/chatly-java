package com.chatlychat.chatly;

import static com.chatlychat.chatly.SurfaceIds.BOT;
import static com.chatlychat.chatly.SurfaceIds.STYLE_GUIDE;

import com.chatlychat.chatly.SurfaceCalls.Entry;
import java.util.List;
import java.util.Map;

/**
 * The AI and automation namespaces: {@code ai}, {@code bots}, {@code bot_routes},
 * {@code canvas_steps}, {@code docs_sources}, {@code knowledge_gaps} and {@code triggers}.
 *
 * <p>45 of the 104 routes added when this SDK's surface was completed on 2026-10-14. Three of the
 * SDK's thirteen paged routes are in here — {@code bots.list}, {@code bots.deliveries} and
 * {@code triggers.list} — and all three were missing from the manifest's {@code paged} flag at
 * first; each one ends in {@code buildPaginatedResponse} in the API, which is the thing to check
 * when a route's envelope is in doubt.
 *
 * <p>No assertions live here; see {@link SurfaceEntriesCore} for why the entries are split across
 * four files at all.
 */
final class SurfaceEntriesAutomation {

  static void add(List<Entry> e) {

    // ------------------------------------------------------------ ai
    e.add(new Entry("ai", "add_provider", "POST", "/v1/ai-providers",
        "/v1/ai-providers",
        c -> c.ai().addProvider("openai", "sk-test")));
    e.add(new Entry("ai", "create_tool", "POST", "/v1/ai/tools",
        "/v1/ai/tools",
        c -> c.ai().createTool(
            Map.of("name", "lookup_order", "description", "Look up an order",
                "urlTemplate", "https://acme.test/orders/{id}"))));
    e.add(new Entry("ai", "delete_tool", "DELETE", "/v1/ai/tools/{id}",
        "/v1/ai/tools/tool_1",
        c -> c.ai().deleteTool("tool_1")));
    e.add(new Entry("ai", "generate_tone", "POST", "/v1/ai/tone/generate",
        "/v1/ai/tone/generate",
        c -> c.ai().generateTone(STYLE_GUIDE)));
    e.add(new Entry("ai", "providers", "GET", "/v1/ai-providers",
        "/v1/ai-providers",
        c -> c.ai().providers()));
    e.add(new Entry("ai", "quota", "GET", "/v1/ai/quota",
        "/v1/ai/quota",
        c -> c.ai().quota()));
    e.add(new Entry("ai", "set_tone", "PUT", "/v1/ai/tone",
        "/v1/ai/tone",
        c -> c.ai().setTone(List.of(Map.of("category", "greeting", "text", "Be brief")))));
    e.add(new Entry("ai", "test_provider", "POST", "/v1/ai-providers/{provider}/test",
        "/v1/ai-providers/openai/test",
        c -> c.ai().testProvider("openai")));
    e.add(new Entry("ai", "tone", "GET", "/v1/ai/tone",
        "/v1/ai/tone",
        c -> c.ai().tone()));
    e.add(new Entry("ai", "tone_preview", "GET", "/v1/ai/tone/preview",
        "/v1/ai/tone/preview",
        c -> c.ai().tonePreview()));
    e.add(new Entry("ai", "tools", "GET", "/v1/ai/tools",
        "/v1/ai/tools",
        c -> c.ai().tools()));
    e.add(new Entry("ai", "update_tool", "PATCH", "/v1/ai/tools/{id}",
        "/v1/ai/tools/tool_1",
        c -> c.ai().updateTool("tool_1", Map.of("description", "Look it up"))));

    // ---------------------------------------------------- bot_routes
    e.add(new Entry("bot_routes", "create", "POST", "/v1/bot-routes",
        "/v1/bot-routes",
        c -> c.botRoutes().create(Map.of("botId", BOT, "name", "Billing questions"))));
    e.add(new Entry("bot_routes", "delete", "DELETE", "/v1/bot-routes/{id}",
        "/v1/bot-routes/brt_1",
        c -> c.botRoutes().delete("brt_1")));
    e.add(new Entry("bot_routes", "fields", "GET", "/v1/bot-routes/fields",
        "/v1/bot-routes/fields",
        c -> c.botRoutes().fields()));
    e.add(new Entry("bot_routes", "list", "GET", "/v1/bot-routes",
        "/v1/bot-routes",
        c -> c.botRoutes().list()));
    e.add(new Entry("bot_routes", "resolve", "POST", "/v1/bot-routes/resolve",
        "/v1/bot-routes/resolve",
        c -> c.botRoutes().resolve(Map.of("page", Map.of("path", "/pricing")))));
    e.add(new Entry("bot_routes", "update", "PATCH", "/v1/bot-routes/{id}",
        "/v1/bot-routes/brt_1",
        c -> c.botRoutes().update("brt_1", Map.of("name", "Renamed"))));

    // ---------------------------------------------------------- bots
    e.add(new Entry("bots", "analytics", "GET", "/v1/bots/{id}/analytics",
        "/v1/bots/" + BOT + "/analytics",
        c -> c.bots().analytics(BOT, 30)));
    e.add(new Entry("bots", "create", "POST", "/v1/bots",
        "/v1/bots",
        c -> c.bots().create(Map.of("name", "Support assistant"))));
    e.add(new Entry("bots", "deliveries", "GET", "/v1/bots/{id}/deliveries",
        "/v1/bots/" + BOT + "/deliveries",
        c -> c.bots().deliveries(BOT)));
    e.add(new Entry("bots", "delivery", "GET", "/v1/bots/{id}/deliveries/{deliveryId}",
        "/v1/bots/" + BOT + "/deliveries/d_1",
        c -> c.bots().delivery(BOT, "d_1")));
    e.add(new Entry("bots", "get", "GET", "/v1/bots/{id}",
        "/v1/bots/" + BOT,
        c -> c.bots().get(BOT)));
    e.add(new Entry("bots", "list", "GET", "/v1/bots",
        "/v1/bots",
        c -> c.bots().list()));
    e.add(new Entry("bots", "redeliver", "POST", "/v1/bots/{id}/deliveries/{deliveryId}/redeliver",
        "/v1/bots/" + BOT + "/deliveries/d_1/redeliver",
        c -> c.bots().redeliver(BOT, "d_1")));
    e.add(new Entry("bots", "rotate_external_secret", "POST", "/v1/bots/{id}/external/rotate-secret",
        "/v1/bots/" + BOT + "/external/rotate-secret",
        c -> c.bots().rotateExternalSecret(BOT)));
    e.add(new Entry("bots", "test_delivery", "POST", "/v1/bots/{id}/test-delivery",
        "/v1/bots/" + BOT + "/test-delivery",
        c -> c.bots().testDelivery(BOT)));
    e.add(new Entry("bots", "update", "PATCH", "/v1/bots/{id}",
        "/v1/bots/" + BOT,
        c -> c.bots().update(BOT, Map.of("name", "Renamed"))));
    e.add(new Entry("bots", "update_external", "PATCH", "/v1/bots/{id}/external",
        "/v1/bots/" + BOT + "/external",
        c -> c.bots().updateExternal(BOT, Map.of("timeoutSeconds", 20))));

    // -------------------------------------------------- canvas_steps
    e.add(new Entry("canvas_steps", "get", "GET", "/v1/canvas-steps/{id}",
        "/v1/canvas-steps/cs_1",
        c -> c.canvasSteps().get("cs_1")));
    e.add(new Entry("canvas_steps", "resolve", "POST", "/v1/canvas-steps/{id}/resolve",
        "/v1/canvas-steps/cs_1/resolve",
        c -> c.canvasSteps().resolve("cs_1", Map.of("blocks", List.of()))));

    // -------------------------------------------------- docs_sources
    e.add(new Entry("docs_sources", "create", "POST", "/v1/docs-sources",
        "/v1/docs-sources",
        c -> c.docsSources().create(Map.of("provider", "gitbook", "token", "tok",
            "organizationId", "org_1", "spaceId", "space_1"))));
    e.add(new Entry("docs_sources", "delete", "DELETE", "/v1/docs-sources/{id}",
        "/v1/docs-sources/ds_1",
        c -> c.docsSources().delete("ds_1")));
    e.add(new Entry("docs_sources", "discover", "POST", "/v1/docs-sources/discover",
        "/v1/docs-sources/discover",
        c -> c.docsSources().discover(Map.of("provider", "gitbook", "token", "tok"))));
    e.add(new Entry("docs_sources", "list", "GET", "/v1/docs-sources",
        "/v1/docs-sources",
        c -> c.docsSources().list()));
    e.add(new Entry("docs_sources", "providers", "GET", "/v1/docs-sources/providers",
        "/v1/docs-sources/providers",
        c -> c.docsSources().providers()));
    e.add(new Entry("docs_sources", "sync", "POST", "/v1/docs-sources/{id}/sync",
        "/v1/docs-sources/ds_1/sync",
        c -> c.docsSources().sync("ds_1", true)));
    e.add(new Entry("docs_sources", "update", "PATCH", "/v1/docs-sources/{id}",
        "/v1/docs-sources/ds_1",
        c -> c.docsSources().update("ds_1", Map.of("label", "Handbook"))));
    e.add(new Entry("docs_sources", "webhook", "POST", "/v1/docs-sources/{id}/webhook",
        "/v1/docs-sources/ds_1/webhook",
        c -> c.docsSources().webhook("ds_1")));

    // ------------------------------------------------ knowledge_gaps
    e.add(new Entry("knowledge_gaps", "draft", "POST", "/v1/knowledge-gaps/{id}/draft",
        "/v1/knowledge-gaps/kg_1/draft",
        c -> c.knowledgeGaps().draft("kg_1")));
    e.add(new Entry("knowledge_gaps", "list", "GET", "/v1/knowledge-gaps",
        "/v1/knowledge-gaps",
        c -> c.knowledgeGaps().list()));
    e.add(new Entry("knowledge_gaps", "update", "PATCH", "/v1/knowledge-gaps/{id}",
        "/v1/knowledge-gaps/kg_1",
        c -> c.knowledgeGaps().update("kg_1", "resolved")));

    // ------------------------------------------------------ triggers
    e.add(new Entry("triggers", "create", "POST", "/v1/triggers",
        "/v1/triggers",
        c -> c.triggers().create(Map.of("name", "Welcome", "eventType",
            "conversation.created", "enabled", true, "actions",
            List.of(Map.of("type", "send_message", "params", Map.of("body", "hi")))))));
    e.add(new Entry("triggers", "delete", "DELETE", "/v1/triggers/{id}",
        "/v1/triggers/trg_1",
        c -> c.triggers().delete("trg_1")));
    e.add(new Entry("triggers", "list", "GET", "/v1/triggers",
        "/v1/triggers",
        c -> c.triggers().list()));
  }

  private SurfaceEntriesAutomation() {}
}
