package com.chatlychat.chatly;

import java.util.List;
import java.util.Map;

/**
 * The AI layer's configuration: provider keys, spend, tone of voice, and custom tools.
 *
 * <p>Reached as {@code chatly.ai()}.
 *
 * <p>Four unrelated controllers sit behind this one namespace, and the paths say so — provider
 * credentials live under {@code /v1/ai-providers} while everything else is under {@code /v1/ai/…}.
 * They are grouped here because they are one subject from a caller's point of view, and split in
 * the API because they are three different permissions: {@code ai:read}/{@code ai:write} for tone
 * and tools, and {@code workspace:billing:read} for {@link #quota()} — which is the one that
 * surprises people, because reading your AI spend is a billing question rather than an AI one.
 */
public final class AiResource extends Resource {

  private static final String PROVIDERS = "/v1/ai-providers";
  private static final String TONE = "/v1/ai/tone";
  private static final String TOOLS = "/v1/ai/tools";

  /** The server's floor on a pasted style guide. Below this, {@link #generateTone} is a 400. */
  private static final int MIN_STYLE_GUIDE = 40;

  /** And its ceiling. */
  private static final int MAX_STYLE_GUIDE = 20_000;

  AiResource(Transport transport) {
    super(transport);
  }

  // ------------------------------------------------------------- providers

  /**
   * Which AI providers this workspace has keys for. {@code GET /v1/ai-providers}
   *
   * <p>An object — {@code {"providers": [...]}} — not a bare array, and it lists <em>every</em>
   * supported provider whether or not one is configured, so a {@code providers} array of length
   * three does not mean three keys are stored. Read each row's status.
   *
   * <p>No response on this controller ever carries a key. The most a row holds is {@code keyHint},
   * four characters, which is deliberate: a settings export or a screenshot of this call leaks
   * nothing worth having.
   */
  public Map<String, Object> providers() {
    return object(transport.get(PROVIDERS, Query.of()), PROVIDERS);
  }

  /**
   * Store or replace a provider's API key. {@code POST /v1/ai-providers}
   *
   * <p><strong>The key is validated by a real call to the provider before it is stored</strong>,
   * and the result comes back on {@code validation}. Only an authentication-class answer refuses
   * the save — a provider that is down, rate-limiting or returning a 500 leaves you with a stored
   * key and {@code status: "unverified"}, which means "nobody has proven this yet", not "this
   * failed". Branch on {@code validation.status}, not on the HTTP status: the save succeeded with a
   * 201 either way.
   *
   * @param provider the provider key, e.g. {@code "openai"} or {@code "anthropic"}. An unknown
   *     value is a 400 against the server's enum rather than a stored row nothing reads.
   * @param apiKey the provider credential. It leaves this process once, over TLS, and is never
   *     readable back.
   */
  public Map<String, Object> addProvider(String provider, String apiKey) {
    Map<String, Object> payload = body();
    payload.put("provider", required(provider, "provider"));
    payload.put("apiKey", required(apiKey, "apiKey"));
    return object(transport.post(PROVIDERS, Query.of(), payload), PROVIDERS);
  }

  /**
   * Re-check a stored key against the provider. {@code POST /v1/ai-providers/{provider}/test}
   *
   * <p>No request body: the key being tested is the one already stored. Use it after a provider
   * outage, or when an autonomous run starts failing and you need to know whether the credential
   * is the reason. Answers 201 with the same {@code validation} shape {@link #addProvider} returns.
   */
  public Map<String, Object> testProvider(String provider) {
    String path = PROVIDERS + "/" + seg(provider, "provider") + "/test";
    return object(transport.post(path, Query.of(), Map.of()), path);
  }

  /**
   * Month-to-date AI spend, the plan's cap, and a projection. {@code GET /v1/ai/quota}
   *
   * <p>Needs {@code workspace:billing:read}, <em>not</em> {@code ai:read} — so a key scoped for the
   * rest of this namespace gets a 403 here and nothing about the 403 explains why. It is the one
   * route on this resource with a different permission.
   *
   * <p>What it meters is <strong>subsidised</strong> spend: calls on Chatly's own provider keys.
   * Bring-your-own-key spend is recorded against a separate owner and is not bounded by this cap,
   * because the bill for it is not ours. A workspace at 100% of its cap can still run BYOK.
   */
  public Map<String, Object> quota() {
    String path = "/v1/ai/quota";
    return object(transport.get(path, Query.of()), path);
  }

  // ------------------------------------------------------------------ tone

  /**
   * The workspace's tone-of-voice rules. {@code GET /v1/ai/tone}
   *
   * <p>A bare array, ordered by category then position — the order is the one the rendered prompt
   * block uses, so it is meaningful and should not be re-sorted for display. At most 40 rows: eight
   * per category across five categories, which is why the route is pagination-exempt.
   */
  public List<Map<String, Object>> tone() {
    return array(transport.get(TONE, Query.of()), TONE);
  }

  /**
   * Replace the whole rule set. {@code PUT /v1/ai/tone}
   *
   * <p><strong>A replace, not a merge.</strong> Rules absent from {@code rules} are deleted; an
   * empty list clears the workspace's tone entirely. There is no per-rule write route, so a caller
   * adding one rule must send the existing set back with it — read {@link #tone()} first.
   *
   * <p>Returns the stored set, re-read after the write: the same bare array {@link #tone()} gives,
   * so there is no need to follow this with a GET.
   *
   * @param rules at most eight per category. Over that is a 400 naming the category.
   */
  public List<Map<String, Object>> setTone(List<Map<String, Object>> rules) {
    return array(
        transport.put(TONE, Query.of(), Map.of("rules", required(rules, "rules"))), TONE);
  }

  /**
   * Turn a pasted style guide into proposed rules. {@code POST /v1/ai/tone/generate}
   *
   * <p><strong>This saves nothing.</strong> It is one LLM call that reads prose and proposes rules
   * for a human to approve; the approval is {@link #setTone}. A caller that treats the 201 as
   * "tone configured" has configured nothing. (The 201 is only Nest's default for a POST with no
   * {@code @HttpCode} — nothing was created.)
   *
   * @param styleGuide 40 to 20,000 characters. Both bounds are the server's and are checked here,
   *     because a two-word style guide is a 400 that costs a round trip and an LLM call to learn.
   */
  public Map<String, Object> generateTone(String styleGuide) {
    required(styleGuide, "styleGuide");
    if (styleGuide.length() < MIN_STYLE_GUIDE || styleGuide.length() > MAX_STYLE_GUIDE) {
      throw new IllegalArgumentException(
          "generateTone: the API accepts a style guide of "
              + MIN_STYLE_GUIDE
              + " to "
              + MAX_STYLE_GUIDE
              + " characters, got "
              + styleGuide.length());
    }
    String path = TONE + "/generate";
    return object(transport.post(path, Query.of(), Map.of("styleGuide", styleGuide)), path);
  }

  /**
   * The exact text the tone rules render into. {@code GET /v1/ai/tone/preview}
   *
   * <p>This is the block prepended to every customer-facing system prompt, so it is the thing to
   * read when an assistant's voice is wrong — the rules are the input, this is what the model
   * actually sees.
   *
   * <p>An empty string for a workspace that has configured nothing. No default house style is
   * invented, so empty here means the model gets no tone instruction at all rather than a built-in
   * one you cannot see.
   */
  public Map<String, Object> tonePreview() {
    String path = TONE + "/preview";
    return object(transport.get(path, Query.of()), path);
  }

  // ----------------------------------------------------------------- tools

  /**
   * The workspace's custom AI tools. {@code GET /v1/ai/tools}
   *
   * <p>A bare array. Each row carries its secret header <em>names</em> and never their values —
   * there is no route on this API that returns a stored tool credential, so a secret you did not
   * record elsewhere is gone, not recoverable.
   */
  public List<Map<String, Object>> tools() {
    return array(transport.get(TOOLS, Query.of()), TOOLS);
  }

  /**
   * Define a tool the autonomous agent can call. {@code POST /v1/ai/tools}
   *
   * <p>Required keys: {@code name}, {@code description}, {@code urlTemplate}. {@code method}
   * defaults to {@code GET} server-side. {@code headers} are stored in the clear and
   * {@code secretHeaders} are encrypted at rest and never returned.
   *
   * <p>Two things the server refuses that are worth knowing before you call. The {@code urlTemplate}
   * is checked against the outbound guard <em>at write time</em>, so a tool aimed at a private
   * address or the cloud metadata endpoint is rejected here rather than discovered when an agent
   * calls it. And a reserved header name — the ones we use to tell your endpoint the request came
   * from us and who it is for — is refused by name.
   *
   * <p>The description is not documentation: it is what the model reads to decide whether to call
   * this tool. A vague one is the usual reason a tool is never used.
   */
  public Map<String, Object> createTool(Map<String, Object> tool) {
    return object(transport.post(TOOLS, Query.of(), required(tool, "tool")), TOOLS);
  }

  /**
   * Change a tool. {@code PATCH /v1/ai/tools/{id}}
   *
   * <p>Partial. <strong>Omit {@code secretHeaders} to keep the stored values</strong> — sending it
   * replaces the whole map, so sending {@code {}} clears every secret the tool had and the next
   * agent call goes out unauthenticated against your endpoint.
   */
  public Map<String, Object> updateTool(String toolId, Map<String, Object> changes) {
    String path = TOOLS + "/" + seg(toolId, "toolId");
    return object(transport.patch(path, Query.of(), required(changes, "changes")), path);
  }

  /**
   * Delete a tool. {@code DELETE /v1/ai/tools/{id}}
   *
   * <p>Answers 200 with {@code {"deleted": true}}, not a 204 — the one real 204 on this SDK's
   * surface is {@code macros.delete}. The returned map is why this is not {@code void}.
   *
   * <p>Bots that list the tool in {@code enabledTools} are not edited: the name simply stops
   * resolving, and a run that would have called it carries on without it.
   */
  public Map<String, Object> deleteTool(String toolId) {
    String path = TOOLS + "/" + seg(toolId, "toolId");
    return object(transport.delete(path, Query.of(), null), path);
  }
}
