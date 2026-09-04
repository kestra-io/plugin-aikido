package io.kestra.plugin.aikido;

import java.util.List;

/**
 * Walks a paginated Aikido list endpoint page by page until a page comes back smaller than the
 * requested page size (the universal termination signal across every Aikido list endpoint,
 * regardless of whether it also exposes a `X-Has-Next-Page` header or a `totalCount` field). A hard
 * page cap guards against an endpoint that ignores paging parameters and would otherwise loop forever.
 */
public final class AikidoPagination {
    private static final int MAX_PAGES = 500;

    private AikidoPagination() {
    }

    @FunctionalInterface
    public interface PageFetcher<T> {
        List<T> fetch(int page) throws Exception;
    }

    @FunctionalInterface
    public interface PageConsumer<T> {
        void accept(List<T> page) throws Exception;
    }

    /** Streams each page to {@code pageConsumer} as it is fetched and returns the total number of items seen. */
    public static <T> long walk(int pageSize, PageFetcher<T> fetcher, PageConsumer<T> pageConsumer) throws Exception {
        var page = 0;
        long total = 0;
        while (true) {
            var items = fetcher.fetch(page);
            pageConsumer.accept(items);
            total += items.size();
            if (items.size() < pageSize) {
                break;
            }
            page++;
            if (page > MAX_PAGES) {
                throw new IllegalStateException("Aikido pagination exceeded " + MAX_PAGES + " pages — the endpoint may be ignoring paging parameters. Narrow the query with filters.");
            }
        }
        return total;
    }
}
