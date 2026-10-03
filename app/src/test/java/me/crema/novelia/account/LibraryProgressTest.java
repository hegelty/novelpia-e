package me.crema.novelia.account;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LibraryProgressTest {
    @Test public void registeredCountDoesNotInventLatestLabelOrCompletion() {
        LibraryPage.Item item = new LibraryPage.Item(null, "", 83, 83, "");
        assertEquals("마지막 읽은 EP.83 · 등록 83편", item.progressLabel());
        assertEquals("", item.latestEpisodeLabel);
        assertFalse(item.hasNextEpisode());
        assertEquals("마지막 읽은 EP.83 · 최신 EP.79", item.withLatestEpisodeLabel("EP.79").progressLabel());
    }

    @Test public void zeroLabelAndUnknownProgressRemainDistinct() {
        LibraryPage.Item item = new LibraryPage.Item(null, "", -1, -1, "");
        assertEquals("", item.progressLabel());
        assertEquals("최신 EP.0", item.withLatestEpisodeLabel("EP.0").progressLabel());
        assertEquals("등록 0편", new LibraryPage.Item(null, "", -1, 0, "").progressLabel());
        assertEquals("마지막 읽은 EP.0 · 최신 EP.0",
                new LibraryPage.Item(null, "", 0, 1, "").withLatestEpisodeLabel("EP.0").progressLabel());
    }

    @Test public void onlyExactEpisodeLabelsAreAccepted() {
        LibraryPage.Item item = new LibraryPage.Item(null, "", -1, 81, "");
        for (String invalid : new String[] {null, "", "EP.1 이어보기", "EP.-1", "EP.1000000000", "81", " EP.79"}) {
            assertEquals("등록 81편", item.withLatestEpisodeLabel(invalid).progressLabel());
        }
        assertEquals("최신 EP.079", item.withLatestEpisodeLabel("EP.079").progressLabel());
    }
}
