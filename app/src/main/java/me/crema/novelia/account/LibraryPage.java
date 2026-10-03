package me.crema.novelia.account;

import me.crema.novelia.site.SiteClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One server-rendered page of an authenticated Novelpia shelf
 * ({@code /mybook/{like|last_view|alarm|collect}/{group}/{sort}/{page}}).
 */
public final class LibraryPage {
    public final List<Item> items;
    public final int page;
    public final String url;
    public final String previousUrl;
    public final String nextUrl;
    /** Route segment of the shelf this page was fetched for ("like", ...). */
    public final String shelf;
    /** Route segment of the group ("0", "-1", "-2", or a positive user group). */
    public final String group;
    /** Route segment of the sort ("date", "view", "list", "vote"). */
    public final String sort;
    /** Exact search keyword supplied to the site, or "" when unset. */
    public final String search;
    /** Filter tabs the server actually rendered next to this list, in site order. */
    public final List<Option> sorts;
    /** Group navigation the server actually rendered next to this list, in site order. */
    public final List<Option> groups;

    /**
     * Backward-compatible constructor. The route fields are derived from
     * {@code url} so legacy callers keep consistent shelf/group/sort/search
     * values; no sort/group options are fabricated for this synthetic shape.
     */
    public LibraryPage(List<Item> items, int page, String url,
                       String previousUrl, String nextUrl) {
        this(items, page, url, previousUrl, nextUrl, selectionOf(url));
    }

    private static String[] selectionOf(String url) {
        String[] selection = LibraryParser.inferSelection(url);
        return selection == null ? new String[] {"like", "0", "date", ""} : selection;
    }

    private LibraryPage(List<Item> items, int page, String url,
                        String previousUrl, String nextUrl, String[] selection) {
        this(items, page, url, previousUrl, nextUrl,
                selection[0], selection[1], selection[2], selection[3],
                Collections.<Option>emptyList(), Collections.<Option>emptyList());
    }

    public LibraryPage(List<Item> items, int page, String url,
                       String previousUrl, String nextUrl, String shelf, String group,
                       String sort, String search, List<Option> sorts, List<Option> groups) {
        this.items = Collections.unmodifiableList(new ArrayList<Item>(items));
        this.page = page;
        this.url = url;
        this.previousUrl = previousUrl == null ? "" : previousUrl;
        this.nextUrl = nextUrl == null ? "" : nextUrl;
        this.shelf = shelf == null ? "" : shelf;
        this.group = group == null ? "" : group;
        this.sort = sort == null ? "" : sort;
        this.search = search == null ? "" : search;
        this.sorts = Collections.unmodifiableList(new ArrayList<Option>(sorts));
        this.groups = Collections.unmodifiableList(new ArrayList<Option>(groups));
    }

    /** A navigation choice the site renders next to the shelf list. */
    public static final class Option {
        public final String value;
        public final String label;

        public Option(String value, String label) {
            this.value = value == null ? "" : value;
            this.label = label == null ? "" : label;
        }

        @Override public String toString() {
            return value + "=" + label;
        }
    }

    /** A novel row and its optional, read-only continue-viewer destination. */
    public static final class Item {
        public final SiteClient.Entry entry;
        public final String continueUrl;
        /**
         * Last-read label shown by the continue button ("EP.794 이어보기"),
         * or -1 when the row carries no such metadata.
         */
        public final int lastReadEpisode;
        /**
         * Total episode count parsed from the row's "회차" metadata,
         * or -1 when metadata is absent or ambiguous (never guessed from the
         * viewer id).
         */
        public final int totalEpisodes;
        /** Author name from the row's writer metadata, "" when absent. */
        public final String author;
        /** Site-supplied ordering key for get_next_episode, NOT derived from a count or viewer ID. */
        public final String nextEpisodeKey;
        public Item(SiteClient.Entry entry, String continueUrl) {
            this(entry, continueUrl, -1, -1, "");
        }

        public Item(SiteClient.Entry entry, String continueUrl,
                    int lastReadEpisode, int totalEpisodes, String author) {
            this(entry, continueUrl, lastReadEpisode, totalEpisodes, author, "");
        }

        public Item(SiteClient.Entry entry, String continueUrl,
                    int lastReadEpisode, int totalEpisodes, String author, String nextEpisodeKey) {
            this.entry = entry;
            this.continueUrl = continueUrl == null ? "" : continueUrl;
            this.lastReadEpisode = lastReadEpisode;
            this.totalEpisodes = totalEpisodes;
            this.author = author == null ? "" : author;
            this.nextEpisodeKey = nextEpisodeKey == null ? "" : nextEpisodeKey;
        }

        public boolean hasNextEpisode() { return !nextEpisodeKey.isEmpty(); }

        /** Episode labels and registered counts are distinct; never infer completion. */
        public String progressLabel() {
            StringBuilder label = new StringBuilder();
            if (hasNextEpisode()) label.append("다음 회차 있음");
            if (lastReadEpisode >= 0) {
                if (label.length() != 0) label.append(" · ");
                label.append("마지막 읽은 EP.").append(lastReadEpisode);
            }
            if (totalEpisodes >= 0) {
                if (label.length() != 0) label.append(" · ");
                label.append("등록 ").append(totalEpisodes).append("편");
            }
            return label.toString();
        }
    }
}
