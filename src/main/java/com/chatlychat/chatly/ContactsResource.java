package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * Contacts — the people your agents talk to.
 *
 * <p>Reached as {@code chatly.contacts()}.
 */
public final class ContactsResource extends Resource {

  private static final String COLLECTION = "/v1/contacts";

  ContactsResource(Transport transport) {
    super(transport);
  }

  /** One page of contacts. {@code GET /v1/contacts} */
  public Page<Map<String, Object>> list() {
    return list(Query.of());
  }

  /**
   * One page of contacts, with query parameters.
   *
   * <p>{@code GET /v1/contacts}
   */
  public Page<Map<String, Object>> list(Query query) {
    return page(COLLECTION, query);
  }

  /**
   * Every contact, pages walked for you.
   *
   * <p>Lazy: no request is made until you start iterating, and it stops the moment you do. See
   * {@link Pager}.
   */
  public Pager<Map<String, Object>> listAuto() {
    return listAuto(Query.of());
  }

  /** Every contact matching {@code query}, pages walked for you. */
  public Pager<Map<String, Object>> listAuto(Query query) {
    return pager(COLLECTION, query);
  }

  /**
   * One contact by id. {@code GET /v1/contacts/{id}}
   *
   * @throws com.chatlychat.chatly.errors.NotFoundException if no such contact exists <em>in this
   *     workspace</em>. A contact in another tenant is a 404, not a 403 — row-level security makes
   *     the two indistinguishable, by design.
   */
  public Map<String, Object> get(String contactId) {
    String path = COLLECTION + "/" + seg(contactId, "contactId");
    return object(transport.get(path, Query.of()), path);
  }

  /**
   * Create or update a contact. {@code POST /v1/contacts}
   *
   * <p>Upsert, not insert: the server matches on {@code externalId} (falling back to {@code email})
   * and updates the existing row when it finds one. Pass a map of the fields you want set —
   * {@code externalId}, {@code email}, {@code phone}, {@code name}, {@code attributes}.
   *
   * <p><strong>Every field is optional server-side.</strong> {@code upsert(Map.of())} validates and
   * creates an anonymous contact; it does not error. If you expect a match, make sure
   * {@code externalId} is actually in the map — a typo in that key name creates a new contact on
   * every call instead of updating one, and nothing about the 201 response tells you so.
   */
  public Map<String, Object> upsert(Map<String, Object> contact) {
    return object(transport.post(COLLECTION, Query.of(), required(contact, "contact")), COLLECTION);
  }

  /**
   * Upsert up to 5000 contacts in one request. {@code POST /v1/contacts/bulk}
   *
   * <p>The server requires between 1 and 5000 rows; both bounds are checked here so an oversized
   * batch fails before it is serialised and sent.
   */
  public Map<String, Object> bulkUpsert(List<Map<String, Object>> contacts) {
    required(contacts, "contacts");
    if (contacts.isEmpty()) {
      throw new IllegalArgumentException("bulkUpsert: contacts must not be empty");
    }
    if (contacts.size() > 5000) {
      throw new IllegalArgumentException(
          "bulkUpsert: the API accepts at most 5000 contacts per request, got " + contacts.size());
    }
    String path = COLLECTION + "/bulk";
    return object(transport.post(path, Query.of(), Map.of("contacts", contacts)), path);
  }

  /**
   * Merge one contact into another. {@code POST /v1/contacts/{id}/merge}
   *
   * @param contactId the contact that survives
   * @param mergedId the contact that is absorbed and ceases to exist as a separate record
   */
  public Map<String, Object> merge(String contactId, String mergedId) {
    String path = COLLECTION + "/" + seg(contactId, "contactId") + "/merge";
    return object(
        transport.post(path, Query.of(), Map.of("mergedId", required(mergedId, "mergedId"))), path);
  }

  /**
   * Add a tag to a contact. {@code POST /v1/contacts/{id}/tags}
   *
   * <p>Singular, despite the plural name: the route takes exactly one {@code tag} per request. The
   * name is {@code addTags} because the SDK surface list is shared across eight languages and the
   * entry there is {@code add_tags}; {@link #addTag} is the same call under the name that reads
   * correctly. Tag several by calling it several times.
   */
  public Map<String, Object> addTags(String contactId, String tag) {
    String path = COLLECTION + "/" + seg(contactId, "contactId") + "/tags";
    return object(transport.post(path, Query.of(), Map.of("tag", required(tag, "tag"))), path);
  }

  /** Alias for {@link #addTags}, under a name that matches what it does. */
  public Map<String, Object> addTag(String contactId, String tag) {
    return addTags(contactId, tag);
  }

  /**
   * Remove a tag. {@code DELETE /v1/contacts/{id}/tags/{tag}}
   *
   * <p>The tag is a human-typed name and goes in the path, so it is percent-encoded here. A tag
   * called {@code "needs follow up"} or {@code "50%+"} works; passing it unencoded would either
   * 404 or, with a {@code /} in it, address a different route entirely.
   */
  public Map<String, Object> removeTag(String contactId, String tag) {
    String path =
        COLLECTION + "/" + seg(contactId, "contactId") + "/tags/" + seg(tag, "tag");
    return object(transport.delete(path, Query.of(), null), path);
  }

  /**
   * Set a contact's marketing-unsubscribe flag. {@code POST /v1/contacts/{id}/unsubscribe}
   *
   * @param unsubscribed true to unsubscribe, false to re-subscribe. Required by the server — the
   *     route is a setter, not a toggle, so there is no "flip it" call and no ambiguity about what
   *     state you ended in.
   */
  public Map<String, Object> unsubscribe(String contactId, boolean unsubscribed) {
    String path = COLLECTION + "/" + seg(contactId, "contactId") + "/unsubscribe";
    return object(transport.post(path, Query.of(), Map.of("unsubscribed", unsubscribed)), path);
  }

  /** One page of a contact's activity timeline. {@code GET /v1/contacts/{id}/journey} */
  public Page<Map<String, Object>> journey(String contactId) {
    return journey(contactId, Query.of());
  }

  /** One page of a contact's activity timeline, with query parameters. */
  public Page<Map<String, Object>> journey(String contactId, Query query) {
    return page(COLLECTION + "/" + seg(contactId, "contactId") + "/journey", query);
  }

  /** A contact's whole timeline, pages walked for you. */
  public Pager<Map<String, Object>> journeyAuto(String contactId, Query query) {
    return pager(COLLECTION + "/" + seg(contactId, "contactId") + "/journey", query);
  }
}
