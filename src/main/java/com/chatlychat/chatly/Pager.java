package com.chatlychat.chatly;

import com.chatlychat.chatly.errors.ChatlyException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An auto-paginating view over a list endpoint: walks the cursor chain and yields rows.
 *
 * <pre>{@code
 * // row at a time, pages fetched lazily as you reach them
 * for (Map<String, Object> contact : chatly.contacts().listAuto(Query.of().limit(100))) {
 *   System.out.println(contact.get("email"));
 * }
 *
 * // or as a Stream
 * List<String> emails = chatly.contacts().listAuto().stream()
 *     .map(c -> (String) c.get("email"))
 *     .filter(Objects::nonNull)
 *     .limit(500)                       // stops fetching once 500 are in hand
 *     .toList();
 *
 * // or page at a time, when you want the envelope (facets, total, hasMore)
 * for (Page<Map<String, Object>> page : chatly.contacts().listAuto().pages()) {
 *   System.out.println(page.size() + " of " + page.total().orElse(-1L));
 * }
 * }</pre>
 *
 * <p><strong>Laziness.</strong> Nothing is fetched when the {@code Pager} is constructed. The first
 * page arrives on the first {@code hasNext()}. The stream is sequential and lazily bound, so a
 * {@code limit(n)} genuinely stops the walk rather than draining the whole list first — which
 * matters when the list is a workspace's entire contact book.
 *
 * <p><strong>It is not reusable.</strong> {@link #iterator()} returns a fresh walk each time, so
 * iterating twice performs the request twice. {@link #stream()} likewise. Snapshot with
 * {@code .stream().toList()} if you need to traverse more than once.
 *
 * <p><strong>Not thread-safe.</strong> One iterator per thread.
 *
 * @param <T> element type
 */
public final class Pager<T> implements Iterable<T> {

  /** Fetches one page. {@code cursor} is {@code null} for the first page. */
  @FunctionalInterface
  public interface PageFetcher<T> {
    Page<T> fetch(String cursor);
  }

  private final PageFetcher<T> fetcher;
  private final String describedBy;

  Pager(PageFetcher<T> fetcher, String describedBy) {
    this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    this.describedBy = describedBy;
  }

  @Override
  public Iterator<T> iterator() {
    return new RowIterator();
  }

  /**
   * A sequential {@link Stream} over every row, pages fetched as needed.
   *
   * <p>Deliberately not parallelisable: the cursor chain is strictly sequential — page two's
   * cursor is only knowable once page one has arrived — so there is nothing for a parallel
   * spliterator to split, and a {@code .parallel()} call would spend fork-join threads on a walk
   * that is still one request at a time. {@link Spliterator#ORDERED} is reported because the server's
   * order is meaningful; {@code SIZED} is not, because the total is unknown until the walk ends.
   */
  public Stream<T> stream() {
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED | Spliterator.NONNULL),
        false);
  }

  /** The same walk, a page at a time, when you need the page envelope and not just the rows. */
  public Iterable<Page<T>> pages() {
    return PageIterator::new;
  }

  /**
   * Walk the whole list into memory.
   *
   * <p>Named so that the cost is visible at the call site. A workspace's contacts can be hundreds of
   * thousands of rows and this will hold all of them; prefer {@link #stream()} with a {@code limit}
   * or the {@code for} loop unless you really need the list.
   */
  public List<T> toList() {
    return stream().toList();
  }

  /** Walks pages; each row iterator is built on top of this. */
  private final class PageIterator implements Iterator<Page<T>> {
    private String cursor;
    private boolean exhausted;
    private boolean started;
    private Page<T> pending;

    /**
     * Cursors already requested.
     *
     * <p>This is the loop guard the contract requires. A server that repeats a cursor — a bug, but
     * one that has happened — turns an auto-paginator into an infinite loop that reads the same page
     * forever while the process memory climbs. Spending a few bytes per page to notice is worth it.
     */
    private final Set<String> seen = new HashSet<>();

    @Override
    public boolean hasNext() {
      if (pending != null) {
        return true;
      }
      if (exhausted) {
        return false;
      }
      if (started && cursor == null) {
        exhausted = true;
        return false;
      }
      started = true;
      if (cursor != null && !seen.add(cursor)) {
        exhausted = true;
        throw new ChatlyException(
            "pagination stopped: "
                + describedBy
                + " returned the cursor '"
                + cursor
                + "' twice, which would loop forever. This is a server-side bug; please report it"
                + " with the request id of the last page.",
            0,
            "pagination_cursor_repeated",
            null,
            null,
            null);
      }
      pending = fetcher.fetch(cursor);
      cursor = pending.nextCursor().orElse(null);
      if (cursor == null) {
        // Last page. Note we still return `pending` below; `exhausted` is set
        // on the NEXT call. Stopping here would drop the final page, which is
        // the classic off-by-one in a cursor walk.
        exhausted = false;
      }
      return true;
    }

    @Override
    public Page<T> next() {
      if (!hasNext()) {
        throw new NoSuchElementException("no more pages");
      }
      Page<T> out = pending;
      pending = null;
      if (cursor == null) {
        exhausted = true;
      }
      return out;
    }
  }

  /** Flattens {@link PageIterator} into rows. */
  private final class RowIterator implements Iterator<T> {
    private final PageIterator pages = new PageIterator();
    private Iterator<T> rows = java.util.Collections.emptyIterator();

    @Override
    public boolean hasNext() {
      // `while`, not `if`: an empty page in the middle of a chain is legal (a
      // filter can exclude everything on one page while later pages still
      // match) and an `if` would stop the walk there.
      while (!rows.hasNext()) {
        if (!pages.hasNext()) {
          return false;
        }
        rows = pages.next().items().iterator();
      }
      return true;
    }

    @Override
    public T next() {
      if (!hasNext()) {
        throw new NoSuchElementException("no more rows");
      }
      return rows.next();
    }
  }
}
