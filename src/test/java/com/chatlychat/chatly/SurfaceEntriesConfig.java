package com.chatlychat.chatly;

import static com.chatlychat.chatly.SurfaceIds.KIND;

import com.chatlychat.chatly.SurfaceCalls.Entry;
import java.util.List;
import java.util.Map;

/**
 * The configuration namespaces: {@code billing}, {@code brands}, {@code channels},
 * {@code customer_cards}, {@code customer_card_endpoints}, {@code email_templates},
 * {@code webhooks} and {@code workspace}.
 *
 * <p>35 entries: 33 of the 104 routes added when this SDK's surface was completed, plus
 * {@code webhooks.delete} and {@code workspace.mine}, which were key-accessible all along and
 * reached the manifest two days later. Two entries here are worth a
 * second look at the lock they produce: {@code channels.adapters} is the one {@code array} route
 * whose elements are strings rather than objects, and {@code billing.invoices} is
 * cursor-paginated and still {@code object}, because its envelope key is {@code data} rather than
 * {@code items}.
 *
 * <p>No assertions live here; see {@link SurfaceEntriesCore} for why the entries are split across
 * four files at all.
 */
final class SurfaceEntriesConfig {

  static void add(List<Entry> e) {

    // ------------------------------------------------------- billing
    e.add(new Entry("billing", "invoices", "GET", "/v1/billing/invoices",
        "/v1/billing/invoices",
        c -> c.billing().invoices()));
    e.add(new Entry("billing", "summary", "GET", "/v1/billing",
        "/v1/billing",
        c -> c.billing().summary()));
    e.add(new Entry("billing", "usage_daily", "GET", "/v1/billing/usage/daily",
        "/v1/billing/usage/daily",
        c -> c.billing().usageDaily("ai_tokens")));
    e.add(new Entry("billing", "usage_monthly", "GET", "/v1/billing/usage/monthly",
        "/v1/billing/usage/monthly",
        c -> c.billing().usageMonthly()));

    // -------------------------------------------------------- brands
    e.add(new Entry("brands", "create", "POST", "/v1/brands",
        "/v1/brands",
        c -> c.brands().create(Map.of("name", "Acme", "slug", "acme"))));
    e.add(new Entry("brands", "delete", "DELETE", "/v1/brands/{id}",
        "/v1/brands/bd_1",
        c -> c.brands().delete("bd_1")));
    e.add(new Entry("brands", "list", "GET", "/v1/brands",
        "/v1/brands",
        c -> c.brands().list()));
    e.add(new Entry("brands", "update", "PATCH", "/v1/brands/{id}",
        "/v1/brands/bd_1",
        c -> c.brands().update("bd_1", Map.of("name", "Acme Ltd"))));

    // ------------------------------------------------------ channels
    e.add(new Entry("channels", "adapters", "GET", "/v1/channels/adapters",
        "/v1/channels/adapters",
        c -> c.channels().adapters()));
    e.add(new Entry("channels", "create", "POST", "/v1/channels",
        "/v1/channels",
        c -> c.channels().create(
            Map.of("type", "api", "name", "Our backend", "publicId", "our-backend"))));
    e.add(new Entry("channels", "rotate_secret", "POST", "/v1/channels/{id}/secret/rotate",
        "/v1/channels/ch_1/secret/rotate",
        c -> c.channels().rotateSecret("ch_1")));
    e.add(new Entry("channels", "update", "PATCH", "/v1/channels/{id}",
        "/v1/channels/ch_1",
        c -> c.channels().update("ch_1", Map.of("enabled", false))));

    // --------------------------------------- customer_card_endpoints
    e.add(new Entry("customer_card_endpoints", "activate_secret", "POST", "/v1/customer-card-endpoints/{id}/activate-secret",
        "/v1/customer-card-endpoints/cce_1/activate-secret",
        c -> c.customerCardEndpoints().activateSecret("cce_1")));
    e.add(new Entry("customer_card_endpoints", "create", "POST", "/v1/customer-card-endpoints",
        "/v1/customer-card-endpoints",
        c -> c.customerCardEndpoints().create("https://acme.test/cards")));
    e.add(new Entry("customer_card_endpoints", "delete", "DELETE", "/v1/customer-card-endpoints/{id}",
        "/v1/customer-card-endpoints/cce_1",
        c -> c.customerCardEndpoints().delete("cce_1")));
    e.add(new Entry("customer_card_endpoints", "list", "GET", "/v1/customer-card-endpoints",
        "/v1/customer-card-endpoints",
        c -> c.customerCardEndpoints().list()));
    e.add(new Entry("customer_card_endpoints", "rotate_secret", "POST", "/v1/customer-card-endpoints/{id}/rotate-secret",
        "/v1/customer-card-endpoints/cce_1/rotate-secret",
        c -> c.customerCardEndpoints().rotateSecret("cce_1")));

    // ------------------------------------------------ customer_cards
    e.add(new Entry("customer_cards", "create", "POST", "/v1/customer-cards",
        "/v1/customer-cards",
        c -> c.customerCards().create(
            Map.of("key", "orders", "title", "Recent orders", "url", "https://acme.test/orders"))));
    e.add(new Entry("customer_cards", "delete", "DELETE", "/v1/customer-cards/{id}",
        "/v1/customer-cards/cc_1",
        c -> c.customerCards().delete("cc_1")));
    e.add(new Entry("customer_cards", "list", "GET", "/v1/customer-cards",
        "/v1/customer-cards",
        c -> c.customerCards().list()));
    e.add(new Entry("customer_cards", "rotate_secret", "POST", "/v1/customer-cards/{id}/rotate-secret",
        "/v1/customer-cards/cc_1/rotate-secret",
        c -> c.customerCards().rotateSecret("cc_1")));
    e.add(new Entry("customer_cards", "update", "PATCH", "/v1/customer-cards/{id}",
        "/v1/customer-cards/cc_1",
        c -> c.customerCards().update("cc_1", Map.of("title", "Orders"))));

    // ----------------------------------------------- email_templates
    e.add(new Entry("email_templates", "delete", "DELETE", "/v1/email-templates/{kind}",
        "/v1/email-templates/" + KIND,
        c -> c.emailTemplates().delete(KIND)));
    e.add(new Entry("email_templates", "list", "GET", "/v1/email-templates",
        "/v1/email-templates",
        c -> c.emailTemplates().list()));
    e.add(new Entry("email_templates", "preview", "POST", "/v1/email-templates/preview",
        "/v1/email-templates/preview",
        c -> c.emailTemplates().preview(KIND, "Join {{workspace}}", "Hello")));
    e.add(new Entry("email_templates", "update", "PUT", "/v1/email-templates/{kind}",
        "/v1/email-templates/" + KIND,
        c -> c.emailTemplates().update(KIND, "Join {{workspace}}", "Hello")));

    // ------------------------------------------------------ webhooks
    e.add(new Entry("webhooks", "create", "POST", "/v1/webhooks",
        "/v1/webhooks",
        c -> c.webhooks().create(Map.of("url", "https://acme.test/hook",
            "eventTypes", List.of("conversation.created")))));
    e.add(new Entry("webhooks", "delete", "DELETE", "/v1/webhooks/{id}",
        "/v1/webhooks/wh_1",
        c -> c.webhooks().delete("wh_1")));
    e.add(new Entry("webhooks", "events", "GET", "/v1/webhooks/events",
        "/v1/webhooks/events",
        c -> c.webhooks().events()));
    e.add(new Entry("webhooks", "list", "GET", "/v1/webhooks",
        "/v1/webhooks",
        c -> c.webhooks().endpoints()));
    e.add(new Entry("webhooks", "schema_versions", "GET", "/v1/webhooks/schema-versions",
        "/v1/webhooks/schema-versions",
        c -> c.webhooks().schemaVersions()));
    e.add(new Entry("webhooks", "set_schema_version", "PATCH", "/v1/webhooks/{id}/schema-version",
        "/v1/webhooks/wh_1/schema-version",
        c -> c.webhooks().setSchemaVersion("wh_1", "2026-01-01")));

    // ----------------------------------------------------- workspace
    e.add(new Entry("workspace", "key_scopes", "GET", "/v1/api-keys/scopes",
        "/v1/api-keys/scopes",
        c -> c.workspace().keyScopes()));
    e.add(new Entry("workspace", "mine", "GET", "/v1/workspaces/me",
        "/v1/workspaces/me",
        c -> c.workspace().mine()));
    e.add(new Entry("workspace", "secrets", "GET", "/v1/workspace/secrets",
        "/v1/workspace/secrets",
        c -> c.workspace().secrets()));
  }

  private SurfaceEntriesConfig() {}
}
