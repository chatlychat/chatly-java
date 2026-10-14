package com.chatlychat.chatly;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Chatly backend API client.
 *
 * <pre>{@code
 * Chatly chatly = Chatly.builder().apiKey(System.getenv("CHATLY_API_KEY")).build();
 *
 * Map<String, Object> me = chatly.whoami();
 * Map<String, Object> contact = chatly.contacts().upsert(Map.of(
 *     "externalId", "u-42", "email", "jamie@acme.io", "name", "Jamie"));
 * chatly.conversations().send(conversationId, "Thanks — looking into it now.");
 * }</pre>
 *
 * <h2>This is a backend SDK. It must never run in a browser or a mobile app.</h2>
 *
 * A {@code ck_} API key is a bearer credential for your <strong>entire workspace</strong> — every
 * conversation, every contact, every message. Shipping one to a client means publishing it: anyone
 * who opens dev tools or unzips an APK has it, and the only remedy is revoking the key and
 * redeploying. For in-browser chat use {@code @chatlychat/sdk-js}, which authenticates per visitor
 * and holds no workspace credential; for mobile, the Kotlin and Swift SDKs.
 *
 * <h2>Threading and lifecycle</h2>
 *
 * Immutable and thread-safe. Build one per process and share it: it holds an {@link HttpClient},
 * whose connection pool is the thing you want reused. Every call blocks the calling thread; there is
 * no async variant in 0.1.0, so call from a virtual thread or an executor if you need concurrency.
 *
 * <h2>What the methods return</h2>
 *
 * {@code Map<String, Object>} for an object, {@code List<Map<String, Object>>} for an array,
 * {@link Page} for a paginated list. Not generated POJOs — see "Why maps and not POJOs" in the
 * README; the short version is that this SDK has zero runtime dependencies and therefore no
 * reflective binder, and hand-written response types for 159 routes would be a second schema to
 * keep in sync with the API.
 *
 * @see <a href="https://chatlychat.com/docs">chatlychat.com/docs</a>
 */
public final class Chatly {

  /** This SDK's version. Sent as part of the {@code User-Agent} on every request. */
  public static final String VERSION = "0.1.0";

  /**
   * The production API.
   *
   * <p>This is the default, and it is a real host that resolves. Four of this product's SDKs once
   * defaulted to {@code api.livechat.local} or {@code api.chatly.example} — neither resolves
   * anywhere — so every call failed for anyone who followed the published quickstart and did not
   * override it. A customer reported it; no test caught it, because every test in every one of those
   * SDKs passed an explicit URL. This SDK's suite asserts on this constant directly for that reason.
   *
   * <p>A self-hosted install overrides it with {@link Builder#baseUrl}. That is the only caller that
   * ever should.
   */
  public static final String DEFAULT_BASE_URL = "https://api.chatlychat.com";

  /** Default per-request timeout. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

  private final Transport transport;

  private final ContactsResource contacts;
  private final ConversationsResource conversations;
  private final TicketsResource tickets;
  private final MacrosResource macros;
  private final KbResource kb;
  private final SearchResource search;
  private final EventsResource events;
  private final WebhooksResource webhooks;
  private final ReportsResource reports;
  private final ChannelsResource channels;
  private final BillingResource billing;
  private final WorkspaceResource workspace;
  private final AiResource ai;
  private final BotsResource bots;
  private final BotRoutesResource botRoutes;
  private final BrandsResource brands;
  private final CanvasStepsResource canvasSteps;
  private final CustomerCardsResource customerCards;
  private final CustomerCardEndpointsResource customerCardEndpoints;
  private final DocsSourcesResource docsSources;
  private final EmailTemplatesResource emailTemplates;
  private final KnowledgeGapsResource knowledgeGaps;
  private final TriggersResource triggers;

  private Chatly(Transport transport) {
    this.transport = transport;
    // Every namespace is bound here, against THIS instance's transport.
    //
    // The Node SDK's equivalent of this had a derived-client path that rebound
    // four of its five namespaces and forgot the fifth, so a client derived
    // with a pinned idempotency key still dispatched `search` through the
    // parent. It was harmless only because `search` happens to be read-only.
    // Binding all of them in one constructor, with `withIdempotencyKey` going
    // back through that same constructor, makes the whole class of bug
    // unreachable rather than merely absent today.
    this.contacts = new ContactsResource(transport);
    this.conversations = new ConversationsResource(transport);
    this.tickets = new TicketsResource(transport);
    this.macros = new MacrosResource(transport);
    this.kb = new KbResource(transport);
    this.search = new SearchResource(transport);
    this.events = new EventsResource(transport);
    this.webhooks = new WebhooksResource(transport);
    this.reports = new ReportsResource(transport);
    this.channels = new ChannelsResource(transport);
    this.billing = new BillingResource(transport);
    this.workspace = new WorkspaceResource(transport);
    this.ai = new AiResource(transport);
    this.bots = new BotsResource(transport);
    this.botRoutes = new BotRoutesResource(transport);
    this.brands = new BrandsResource(transport);
    this.canvasSteps = new CanvasStepsResource(transport);
    this.customerCards = new CustomerCardsResource(transport);
    this.customerCardEndpoints = new CustomerCardEndpointsResource(transport);
    this.docsSources = new DocsSourcesResource(transport);
    this.emailTemplates = new EmailTemplatesResource(transport);
    this.knowledgeGaps = new KnowledgeGapsResource(transport);
    this.triggers = new TriggersResource(transport);
  }

  /** Start building a client. {@link Builder#apiKey} is the only required input. */
  public static Builder builder() {
    return new Builder();
  }

  /** A client with default everything. Equivalent to {@code builder().apiKey(key).build()}. */
  public static Chatly of(String apiKey) {
    return builder().apiKey(apiKey).build();
  }

  // ------------------------------------------------------------- namespaces

  /** Contacts — the people your agents talk to. */
  public ContactsResource contacts() {
    return contacts;
  }

  /** Conversations and the messages in them. */
  public ConversationsResource conversations() {
    return conversations;
  }

  /** Tickets. */
  public TicketsResource tickets() {
    return tickets;
  }

  /** Macros — saved replies and action bundles. */
  public MacrosResource macros() {
    return macros;
  }

  /** The knowledge base. */
  public KbResource kb() {
    return kb;
  }

  /** Full-text search. */
  public SearchResource search() {
    return search;
  }

  /** The CDP event stream. */
  public EventsResource events() {
    return events;
  }

  /**
   * Webhook delivery history. For verifying an incoming delivery's signature, see the static
   * {@link Webhooks} helper instead — that needs no client and no key.
   */
  public WebhooksResource webhooks() {
    return webhooks;
  }

  /** Reporting aggregates. */
  public ReportsResource reports() {
    return reports;
  }

  /** Channels. */
  public ChannelsResource channels() {
    return channels;
  }

  /** Plans and metered usage. */
  public BillingResource billing() {
    return billing;
  }

  /** Workspace settings, labels, inboxes. */
  public WorkspaceResource workspace() {
    return workspace;
  }

  /** AI configuration: provider keys, spend, tone of voice, custom tools. */
  public AiResource ai() {
    return ai;
  }

  /** Bots — Chatly personas, and your own push-delivered agents. */
  public BotsResource bots() {
    return bots;
  }

  /** Bot routes — which assistant answers, and under what conditions. */
  public BotRoutesResource botRoutes() {
    return botRoutes;
  }

  /** Brands — sub-tenants inside one workspace. */
  public BrandsResource brands() {
    return brands;
  }

  /** Canvas steps — one turn of an interactive card. */
  public CanvasStepsResource canvasSteps() {
    return canvasSteps;
  }

  /** Customer cards — inbox panels fed from your backend. */
  public CustomerCardsResource customerCards() {
    return customerCards;
  }

  /** Per-url signing secrets for customer cards, with a two-step rotation. */
  public CustomerCardEndpointsResource customerCardEndpoints() {
    return customerCardEndpoints;
  }

  /** Documentation sources synced into the knowledge base. */
  public DocsSourcesResource docsSources() {
    return docsSources;
  }

  /** The five overridable transactional emails. */
  public EmailTemplatesResource emailTemplates() {
    return emailTemplates;
  }

  /** Questions the knowledge base could not answer. */
  public KnowledgeGapsResource knowledgeGaps() {
    return knowledgeGaps;
  }

  /** Triggers — event-driven rules. */
  public TriggersResource triggers() {
    return triggers;
  }

  // ------------------------------------------------------------------- root

  /**
   * Describe the credential this client is using: its workspace and its scopes.
   *
   * <p>{@code GET /v1/api-keys/whoami}
   *
   * <p><strong>Make this your first call.</strong> It turns a mis-scoped or wrong-workspace key into
   * an obvious error at integration time, rather than a 403 from an unrelated endpoint three weeks
   * later. It is also deliberately the <em>only</em> route on the api-keys controller that accepts an
   * API key — managing keys with a key is a privilege-escalation shape — so it is the one call that
   * tells you about your key without needing a human session.
   *
   * @return at least {@code workspaceId} and {@code scopes}
   */
  public Map<String, Object> whoami() {
    String path = "/v1/api-keys/whoami";
    Object decoded = transport.get(path, Query.of());
    return Resource.object(decoded, path);
  }

  /**
   * Describe whoever is calling — a person, or this key. {@code GET /v1/me}
   *
   * <p><strong>The liveness check.</strong> It is the one route on the surface with no
   * {@code @RequirePermission} at all, deliberately: what a key learns here is its OWN workspace id
   * and its OWN grant list, the two things it presented in order to ask, so a scope requirement
   * could only withhold a credential's description from the credential itself. A key with no useful
   * scope still gets a 200, and that answer is true — which is what makes this the right call for
   * "is my configuration alive?".
   *
   * <p>The body is <strong>discriminated on {@code principal}</strong>. For a {@code ck_} key it is
   * {@code "api_key"} and the rest of the object is the same one {@link #whoami()} returns: there
   * is no user behind a key. A dashboard session gets {@code "user"} and a person. So branch on
   * {@code principal} rather than assuming the shape — a backend SDK will only ever see the key
   * half, but the same route is what the dashboard calls on boot.
   *
   * <p>How to choose between this and {@link #whoami()}: they answer the same thing for a key, and
   * {@code whoami()} requires {@code workspace:read} while this requires nothing. Use this to prove
   * a key works at all; use {@code whoami()} when you are already inside a scoped flow and want the
   * answer from the api-keys controller itself.
   *
   * <p>It also answers while the workspace is <em>suspended</em> ({@code @AllowWhenSuspended}),
   * because the screen that tells a user they are suspended cannot render without it.
   */
  public Map<String, Object> me() {
    String path = "/v1/me";
    return Resource.object(transport.get(path, Query.of()), path);
  }

  /** The base URL this client is pointed at. Useful in a log line on startup. */
  public String baseUrl() {
    return transport.baseUrl();
  }

  // ------------------------------------------------------------ idempotency

  /**
   * A view of this client that pins one {@code Idempotency-Key} across every write it makes.
   *
   * <p>By default each write mints a fresh UUID, so an SDK-level retry cannot double-write. Pin a
   * key when <em>you</em> are the one retrying and the two attempts are the same logical operation:
   *
   * <pre>{@code
   * String key = UUID.randomUUID().toString();
   * for (int attempt = 0; attempt < 3; attempt++) {
   *   try {
   *     return chatly.withIdempotencyKey(key).contacts().upsert(body);
   *   } catch (ChatlyException e) {
   *     if (!e.retryable()) throw e;
   *     backoff(attempt);
   *   }
   * }
   * }</pre>
   *
   * The server replays its first response for the same key + same body, and answers 409 for the
   * same key + a <em>different</em> body — so do not reuse a key across two operations that differ,
   * and do not hold one for longer than the 24-hour window the server remembers it for.
   *
   * <p>Returns a new client; this one is unchanged.
   *
   * @param key 16 to 128 characters. The server's bounds, checked here: a shorter key is rejected
   *     upstream with a 428 that reads like a missing header rather than a too-short one.
   */
  public Chatly withIdempotencyKey(String key) {
    Objects.requireNonNull(key, "key");
    if (key.length() < 16 || key.length() > 128) {
      throw new IllegalArgumentException(
          "withIdempotencyKey: the API accepts 16-128 characters, got " + key.length()
              + ". A random UUID is a good choice.");
    }
    return new Chatly(transport.withPinnedIdempotencyKey(key));
  }

  // ----------------------------------------------------------- escape hatch

  /**
   * Call any API route directly, through this client's auth, idempotency, retry and error handling.
   *
   * <pre>{@code
   * Object result = chatly.request("GET", "/v1/some/new/route",
   *                                Query.of().add("limit", 10), null);
   * }</pre>
   *
   * <p>140 of the API's 886 routes accept an API key; 55 of those have a typed method on this
   * client. This is how you reach the other 85 without abandoning the SDK and hand-rolling HTTP with
   * a bearer token — which is what people actually do otherwise, and it means losing the retries,
   * the idempotency keys and the typed errors all at once.
   *
   * <p>No response-shape checking happens here, because nothing knows what shape to expect: the
   * return is whatever {@link Json#parse} made of the body — a {@code Map}, a {@code List}, a
   * scalar, or {@code null} for a 204. Cast it yourself, or hand it to
   * {@link Page#fromDecoded} if the route is paginated.
   *
   * @param method {@code GET}, {@code POST}, {@code PATCH}, {@code PUT} or {@code DELETE}. Anything
   *     other than {@code GET} is treated as a write and carries an {@code Idempotency-Key}.
   * @param path an absolute API path beginning with {@code /}, e.g. {@code /v1/contacts}. Already
   *     percent-encode any interpolated id or name — this method cannot tell a path separator from a
   *     slash inside a tag name.
   * @param query query parameters, or {@code null}
   * @param body a value {@link Json#write} can encode (typically a {@code Map}), or {@code null}
   */
  public Object request(String method, String path, Query query, Object body) {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException(
          "request: path must start with '/' and be absolute, e.g. \"/v1/contacts\" (got \""
              + path
              + "\")");
    }
    Query q = query == null ? Query.of() : query;
    return switch (method.toUpperCase(java.util.Locale.ROOT)) {
      case "GET" -> transport.get(path, q);
      case "POST" -> transport.post(path, q, body);
      case "PATCH" -> transport.patch(path, q, body);
      case "PUT" -> transport.put(path, q, body);
      case "DELETE" -> transport.delete(path, q, body);
      default ->
          throw new IllegalArgumentException(
              "request: unsupported method '"
                  + method
                  + "'. Use GET, POST, PATCH, PUT or DELETE.");
    };
  }

  /** {@link #request} with no query and no body. */
  public Object request(String method, String path) {
    return request(method, path, null, null);
  }

  /** {@link #request}, cast to an object. Throws if the route returned an array or a scalar. */
  public Map<String, Object> requestObject(String method, String path, Query query, Object body) {
    return Resource.object(request(method, path, query, body), path);
  }

  /** {@link #request}, cast to an array of objects. Throws if the route returned an object. */
  public List<Map<String, Object>> requestArray(
      String method, String path, Query query, Object body) {
    return Resource.array(request(method, path, query, body), path);
  }

  /**
   * {@link #request} against a cursor-paginated route, auto-paginated.
   *
   * <p>For a paginated route with no typed method here. The route must return
   * {@code {items, nextCursor}} or this throws on the first page.
   */
  public Pager<Map<String, Object>> requestPaginated(String path, Query query) {
    Query base = query == null ? Query.of() : query.copy();
    return new Pager<>(
        cursor -> {
          Query perPage = base.copy();
          if (cursor != null) {
            perPage.cursor(cursor);
          }
          return Page.fromDecoded(transport.get(path, perPage), path);
        },
        path);
  }

  /** Escape-hatch access to the transport, for a test that wants to assert on URL building. */
  Transport transport() {
    return transport;
  }

  // ---------------------------------------------------------------- builder

  /**
   * Builds a {@link Chatly}.
   *
   * <p>Only {@link #apiKey} is required. Every other setting has a working default.
   */
  public static final class Builder {

    private String apiKey;
    private String baseUrl = DEFAULT_BASE_URL;
    private Duration timeout = DEFAULT_TIMEOUT;
    private RetryPolicy retryPolicy = RetryPolicy.defaults();
    private HttpClient httpClient;
    private Transport.Sender sender;
    private String userAgent;

    private Builder() {}

    /**
     * The workspace API key. Required.
     *
     * <p>Starts {@code ck_}. From the dashboard: Settings → API keys. Keep it in an environment
     * variable or a secret manager, not in source.
     */
    public Builder apiKey(String value) {
      this.apiKey = value;
      return this;
    }

    /**
     * Point at a different API host — a self-hosted install, or a staging environment.
     *
     * <p>Include the scheme and no trailing slash: {@code https://chatly.internal.acme.io}. A
     * trailing slash is trimmed rather than producing a double-slashed path, because
     * {@code //v1/contacts} is a different route to most routers and the resulting 404 is baffling.
     */
    public Builder baseUrl(String value) {
      if (value != null) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
          trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
      }
      return this;
    }

    /** Per-request timeout. Default 15 seconds. Applies to each attempt, not to the whole retry
     * sequence — three attempts with a 15s timeout can take 45s plus backoff. */
    public Builder timeout(Duration value) {
      this.timeout = Objects.requireNonNull(value, "timeout");
      return this;
    }

    /** Retry behaviour. Default: 3 attempts, exponential backoff with full jitter. */
    public Builder retryPolicy(RetryPolicy value) {
      this.retryPolicy = Objects.requireNonNull(value, "retryPolicy");
      return this;
    }

    /**
     * Supply your own {@link HttpClient} — for a proxy, a custom {@code SSLContext}, an executor you
     * control, or HTTP/2.
     *
     * <p>Set {@code followRedirects} to {@code NEVER} if you build one. The default client here does,
     * because following a redirect re-sends the {@code Authorization} header to whatever host the
     * {@code Location} named, which hands a workspace-wide credential to a third party. The API does
     * not redirect.
     */
    public Builder httpClient(HttpClient value) {
      this.httpClient = Objects.requireNonNull(value, "httpClient");
      return this;
    }

    /**
     * Replace the HTTP layer entirely.
     *
     * <p>The seam for tests: a {@link Transport.Sender} receives the fully-formed request — auth
     * header, user agent and idempotency key attached — and returns whatever response you like,
     * with no socket involved. Also the way to route requests through a client this SDK knows
     * nothing about (OkHttp, Apache HC) without losing the retry and error layers.
     *
     * <p>Takes precedence over {@link #httpClient}.
     */
    public Builder sender(Transport.Sender value) {
      this.sender = Objects.requireNonNull(value, "sender");
      return this;
    }

    /**
     * Append your own product name to the {@code User-Agent}.
     *
     * <p>The SDK's own token stays first, so {@code userAgent("acme-billing/2.1")} sends
     * {@code chatly-sdk-java/0.1.0 acme-billing/2.1}. Replacing it outright is not offered: the
     * SDK token is how we tell an SDK bug from an integration bug in a support conversation.
     */
    public Builder userAgent(String value) {
      this.userAgent = value;
      return this;
    }

    /**
     * Build the client.
     *
     * @throws IllegalArgumentException if the API key is missing or blank, or the base URL is not
     *     {@code http}/{@code https}. Both are checked <em>here</em>, not on the first call: a
     *     client constructed with no key is a configuration bug, and the place it is easiest to
     *     diagnose is the line that constructed it. An unset {@code CHATLY_API_KEY} environment
     *     variable arrives as {@code null} and would otherwise become the literal header
     *     {@code "Bearer null"} and a puzzling 401 somewhere else entirely.
     */
    public Chatly build() {
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException(
            "Chatly.builder(): apiKey is required and must be non-blank. It is the ck_… workspace"
                + " key from Settings -> API keys; check the environment variable you read it from"
                + " is actually set.");
      }
      if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
        throw new IllegalArgumentException(
            "Chatly.builder(): baseUrl must start with http:// or https://, got \""
                + baseUrl
                + "\"");
      }
      String agent =
          userAgent == null || userAgent.isBlank()
              ? Transport.defaultUserAgent(VERSION)
              : Transport.defaultUserAgent(VERSION) + " " + userAgent.trim();
      Transport.Sender effective =
          sender != null
              ? sender
              : new Transport.JdkSender(
                  httpClient != null ? httpClient : Transport.defaultHttpClient(timeout));
      return new Chatly(
          new Transport(baseUrl, apiKey, agent, timeout, retryPolicy, effective, null));
    }
  }
}
