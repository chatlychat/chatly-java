package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Brands — optional sub-tenants inside one workspace, for a customer who supports several products
 * under several names.
 *
 * <p>Reached as {@code chatly.brands()}.
 *
 * <p>A brand carries the customer-facing identity: logo, colour, support address and the reply
 * domain that notification replies are addressed at. Conversations, channels and help centres can
 * each be filed against one. Everything here is {@code workspace:read} / {@code workspace:write},
 * which is a higher bar than most of this SDK — editing a brand changes what every visitor sees.
 */
public final class BrandsResource extends Resource {

  private static final String COLLECTION = "/v1/brands";

  BrandsResource(Transport transport) {
    super(transport);
  }

  /**
   * Every brand in the workspace, with usage counts. {@code GET /v1/brands} — a bare array.
   *
   * <p>The {@code usage} counts are what make a brand safe to retire: they say how many channels
   * and help centres still point at it. Not paginated — a workspace typically has fewer than ten.
   */
  public List<Map<String, Object>> list() {
    return array(transport.get(COLLECTION, Query.of()), COLLECTION);
  }

  /**
   * Create a brand. {@code POST /v1/brands}
   *
   * <p>Required: {@code name} and {@code slug}. The slug is <strong>not</strong> derived from the
   * name — it must be lowercase alphanumerics and hyphens, and it is rejected rather than
   * normalised, so {@code "Acme Support"} is a 400 and {@code "acme-support"} is what you meant.
   *
   * <p>Optional: {@code logoUrl}, {@code primaryColor} (strict {@code #RRGGBB} — a three-digit hex
   * or a CSS colour name is a 400), {@code supportEmail} and {@code replyDomain}.
   */
  public Map<String, Object> create(Map<String, Object> brand) {
    return object(transport.post(COLLECTION, Query.of(), required(brand, "brand")), COLLECTION);
  }

  /**
   * Change a brand. {@code PATCH /v1/brands/{id}}
   *
   * <p>Partial — every create field is optional here, including {@code slug}. Changing the slug
   * changes anything that addresses the brand by it; prefer not to.
   */
  public Map<String, Object> update(String brandId, Map<String, Object> changes) {
    String path = COLLECTION + "/" + seg(brandId, "brandId");
    return object(transport.patch(path, Query.of(), required(changes, "changes")), path);
  }

  /**
   * Soft-delete a brand. {@code DELETE /v1/brands/{id}}
   *
   * <p><strong>Soft</strong>, and the body is the point: it answers 200 with the id of the row it
   * marked deleted, not a 204. Historical conversations keep their brand reference, so a report
   * over last quarter still renders the brand that was retired yesterday.
   */
  public Map<String, Object> delete(String brandId) {
    String path = COLLECTION + "/" + seg(brandId, "brandId");
    return object(transport.delete(path, Query.of(), null), path);
  }
}
