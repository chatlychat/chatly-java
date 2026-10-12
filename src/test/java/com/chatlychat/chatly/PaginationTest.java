package com.chatlychat.chatly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chatlychat.chatly.errors.ChatlyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CONTRACT.md §10.6 — the auto-paginator walks two pages and stops on a null cursor. */
class PaginationTest {

  private static String pageOf(String cursor, String... ids) {
    StringBuilder items = new StringBuilder("[");
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) {
        items.append(',');
      }
      items.append("{\"id\":\"").append(ids[i]).append("\"}");
    }
    items.append(']');
    String next = cursor == null ? "null" : "\"" + cursor + "\"";
    return "{\"items\":" + items + ",\"nextCursor\":" + next + "}";
  }

  @Test
  @DisplayName("§10.6 walks two pages and stops on a null cursor")
  void walksTwoPagesAndStops() {
    FakeSender sender =
        new FakeSender()
            .then(200, pageOf("cur_2", "c_1", "c_2"))
            .then(200, pageOf(null, "c_3"));

    List<String> ids = new ArrayList<>();
    for (Map<String, Object> contact : sender.client().contacts().listAuto()) {
      ids.add((String) contact.get("id"));
    }

    assertEquals(List.of("c_1", "c_2", "c_3"), ids);
    assertEquals(2, sender.callCount(), "exactly two requests: it must not ask for a third page");
    assertFalse(sender.requests.get(0).url().contains("cursor="), "page one sends no cursor");
    assertTrue(sender.requests.get(1).url().contains("cursor=cur_2"), "page two sends the cursor");
  }

  @Test
  void theStreamViewSeesTheSameRows() {
    FakeSender sender =
        new FakeSender().then(200, pageOf("cur_2", "c_1")).then(200, pageOf(null, "c_2"));

    List<String> ids =
        sender.client().contacts().listAuto().stream().map(c -> (String) c.get("id")).toList();

    assertEquals(List.of("c_1", "c_2"), ids);
  }

  /**
   * A {@code limit} on the stream must stop the walk, not merely trim its result.
   *
   * <p>If the spliterator were eager — or if {@code toList()} happened before the limit — a
   * {@code .limit(1)} over a workspace's whole contact book would still fetch every page. One
   * request is the assertion; two would mean the laziness is decorative.
   */
  @Test
  void aLimitOnTheStreamStopsFetching() {
    FakeSender sender = new FakeSender().then(200, pageOf("cur_2", "c_1", "c_2"));

    List<String> first =
        sender.client().contacts().listAuto().stream()
            .map(c -> (String) c.get("id"))
            .limit(1)
            .toList();

    assertEquals(List.of("c_1"), first);
    assertEquals(1, sender.callCount(), "page two must not have been fetched");
  }

  @Test
  void nothingIsFetchedUntilIterationStarts() {
    FakeSender sender = new FakeSender().then(200, pageOf(null, "c_1"));
    Pager<Map<String, Object>> pager = sender.client().contacts().listAuto();

    assertEquals(0, sender.callCount(), "constructing a Pager must make no request");

    pager.iterator().hasNext();
    assertEquals(1, sender.callCount());
  }

  @Test
  @DisplayName("§10.6 a repeated cursor does not loop forever")
  void aRepeatedCursorIsRefusedRatherThanLoopedOn() {
    // A server bug: page two hands back its own cursor. Without a guard this
    // iterates until the process dies.
    FakeSender sender =
        new FakeSender()
            .then(200, pageOf("cur_loop", "c_1"))
            .then(200, pageOf("cur_loop", "c_2"))
            .then(200, pageOf("cur_loop", "c_3"))
            .then(200, pageOf("cur_loop", "c_4"));

    List<String> seen = new ArrayList<>();
    ChatlyException e =
        assertThrows(
            ChatlyException.class,
            () -> {
              for (Map<String, Object> row : sender.client().contacts().listAuto()) {
                seen.add((String) row.get("id"));
              }
            });

    assertEquals("pagination_cursor_repeated", e.code().orElseThrow());
    assertTrue(e.getMessage().contains("cur_loop"), e.getMessage());
    assertEquals(List.of("c_1", "c_2"), seen, "it yields what it got, then refuses to go round again");
    assertEquals(2, sender.callCount());
  }

  /**
   * An empty page in the middle of a chain is legal and must not end the walk.
   *
   * <p>A filtered list can exclude everything on one page while later pages still match. The
   * flattening iterator has to keep pulling pages until one has rows or the chain ends — an
   * {@code if} where a {@code while} belongs truncates the result silently.
   */
  @Test
  void anEmptyMiddlePageDoesNotEndTheWalk() {
    FakeSender sender =
        new FakeSender()
            .then(200, pageOf("cur_2", "c_1"))
            .then(200, pageOf("cur_3"))
            .then(200, pageOf(null, "c_2"));

    List<String> ids =
        sender.client().contacts().listAuto().stream().map(c -> (String) c.get("id")).toList();

    assertEquals(List.of("c_1", "c_2"), ids);
    assertEquals(3, sender.callCount());
  }

  @Test
  void aSinglePageListIsOneRequest() {
    FakeSender sender = new FakeSender().then(200, pageOf(null, "c_1", "c_2"));
    assertEquals(2, sender.client().contacts().listAuto().toList().size());
    assertEquals(1, sender.callCount());
  }

  @Test
  void anEmptyFirstPageTerminatesCleanly() {
    FakeSender sender = new FakeSender().then(200, pageOf(null));
    assertEquals(List.of(), sender.client().contacts().listAuto().toList());
    assertEquals(1, sender.callCount());
  }

  @Test
  void anEmptyStringCursorIsTreatedAsTheEndNotAsAPageToFetch() {
    // `?cursor=` is not a request for the next page; walking on it would
    // re-fetch page one forever.
    FakeSender sender = new FakeSender().then(200, "{\"items\":[{\"id\":\"c_1\"}],\"nextCursor\":\"\"}");
    assertEquals(1, sender.client().contacts().listAuto().toList().size());
    assertEquals(1, sender.callCount());
  }

  /**
   * Filters must survive onto page two.
   *
   * <p>An auto-paginator that builds page two's query from scratch applies the caller's filter to
   * page one only, and the result is a list that looks filtered and is not.
   */
  @Test
  void theCallersQueryIsCarriedOntoEveryPage() {
    FakeSender sender =
        new FakeSender().then(200, pageOf("cur_2", "c_1")).then(200, pageOf(null, "c_2"));

    Query q = Query.of().limit(2).filter("status:open");
    sender.client().contacts().listAuto(q).toList();

    for (Transport.Request req : sender.requests) {
      assertTrue(req.url().contains("limit=2"), req.url());
      assertTrue(req.url().contains("filter%5B%5D=status%3Aopen"), req.url());
    }
    assertTrue(q.get("cursor").isEmpty(), "the caller's own Query must not be mutated");
  }

  @Test
  void pagesCanBeWalkedAPageAtATimeWithTheEnvelopeIntact() {
    FakeSender sender =
        new FakeSender()
            .then(200, "{\"items\":[{\"id\":\"c_1\"}],\"nextCursor\":\"cur_2\",\"total\":3}")
            .then(200, "{\"items\":[{\"id\":\"c_2\"}],\"nextCursor\":null,\"hasMore\":false}");

    List<Integer> sizes = new ArrayList<>();
    List<Long> totals = new ArrayList<>();
    for (Page<Map<String, Object>> page : sender.client().contacts().listAuto().pages()) {
      sizes.add(page.size());
      page.total().ifPresent(totals::add);
    }

    assertEquals(List.of(1, 1), sizes);
    assertEquals(List.of(3L), totals);
  }

  @Test
  void aSinglePageIsAlsoAvailableUnwalked() {
    FakeSender sender = new FakeSender().then(200, pageOf("cur_2", "c_1"));
    Page<Map<String, Object>> page = sender.client().contacts().list();

    assertEquals(1, page.size());
    assertEquals("cur_2", page.nextCursor().orElseThrow());
    assertTrue(page.hasNextPage());
    assertEquals(1, sender.callCount());
  }

  @Test
  void absentHasMoreIsEmptyNotFalse() {
    FakeSender sender = new FakeSender().then(200, pageOf(null, "c_1"));
    Page<Map<String, Object>> page = sender.client().contacts().list();

    assertTrue(page.hasMore().isEmpty(), "a field the route never sent must not read as false");
    assertTrue(page.total().isEmpty());
  }

  @Test
  void searchExtrasAreReachableThroughRaw() {
    FakeSender sender =
        new FakeSender()
            .then(
                200,
                "{\"items\":[],\"nextCursor\":null,\"facets\":{\"kind\":{\"kb\":2}},\"tier\":\"fts\"}");

    Page<Map<String, Object>> page = sender.client().search().query("refund");

    assertEquals("fts", page.raw().get("tier"));
    assertTrue(page.raw().containsKey("facets"));
  }

  @Test
  void aNonPageBodyFromAPagedRouteIsANamedFailure() {
    FakeSender sender = new FakeSender().then(200, "[{\"id\":\"c_1\"}]");
    ChatlyException e = assertThrows(ChatlyException.class, () -> sender.client().contacts().list());
    assertTrue(e.getMessage().contains("/v1/contacts"), e.getMessage());
  }

  @Test
  void aPageEnvelopeMissingItemsIsANamedFailure() {
    FakeSender sender = new FakeSender().then(200, "{\"nextCursor\":null}");
    ChatlyException e = assertThrows(ChatlyException.class, () -> sender.client().contacts().list());
    assertTrue(e.getMessage().contains("no `items` array"), e.getMessage());
  }

  @Test
  void theEscapeHatchCanAutoPaginateAnUnlistedRoute() {
    FakeSender sender =
        new FakeSender().then(200, pageOf("cur_2", "x_1")).then(200, pageOf(null, "x_2"));

    List<Map<String, Object>> rows =
        sender.client().requestPaginated("/v1/unlisted/things", Query.of().limit(1)).toList();

    assertEquals(2, rows.size());
    assertTrue(sender.requests.get(1).url().contains("cursor=cur_2"));
    assertTrue(sender.requests.get(1).url().contains("limit=1"));
  }
}
