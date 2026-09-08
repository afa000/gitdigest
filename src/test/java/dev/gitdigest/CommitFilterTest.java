package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class CommitFilterTest {

    private static final List<CommitInfo> COMMITS = List.of(
            commit("Alice", "alice@example.com", "2026-08-01T09:00:00"),
            commit("Bob", "bob@other.org", "2026-08-05T09:00:00"),
            commit("Carol", "carol@example.com", "2026-08-10T09:00:00"));

    @Test
    void aFilterWithNoConstraintsKeepsEverything() {
        assertEquals(3, new CommitFilter(null, null, null).apply(COMMITS).size());
    }

    @Test
    void sinceIsInclusive() {
        CommitFilter filter = new CommitFilter(LocalDate.of(2026, 8, 5), null, null);
        List<CommitInfo> kept = filter.apply(COMMITS);

        assertEquals(2, kept.size(), "the commit exactly on the since date must survive");
        assertEquals("Bob", kept.get(0).authorName());
    }

    @Test
    void untilIsInclusive() {
        CommitFilter filter = new CommitFilter(null, LocalDate.of(2026, 8, 5), null);
        List<CommitInfo> kept = filter.apply(COMMITS);

        assertEquals(2, kept.size(), "the commit exactly on the until date must survive");
        assertEquals("Alice", kept.get(0).authorName());
    }

    @Test
    void datesNarrowFromBothEnds() {
        CommitFilter filter = new CommitFilter(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 9), null);
        List<CommitInfo> kept = filter.apply(COMMITS);

        assertEquals(1, kept.size());
        assertEquals("Bob", kept.get(0).authorName());
    }

    @Test
    void authorMatchesNameOrEmailIgnoringCase() {
        assertEquals(1, new CommitFilter(null, null, "alice").apply(COMMITS).size());
        assertEquals(1, new CommitFilter(null, null, "ALICE").apply(COMMITS).size());
        assertEquals(1, new CommitFilter(null, null, "other.org").apply(COMMITS).size());
        // a substring shared by two addresses matches both
        assertEquals(2, new CommitFilter(null, null, "example.com").apply(COMMITS).size());
        assertEquals(0, new CommitFilter(null, null, "nobody").apply(COMMITS).size());
    }

    @Test
    void dateAndAuthorMustBothMatch() {
        CommitFilter filter = new CommitFilter(LocalDate.of(2026, 8, 5), null, "example.com");
        List<CommitInfo> kept = filter.apply(COMMITS);

        assertEquals(1, kept.size());
        assertEquals("Carol", kept.get(0).authorName());
    }

    @Test
    void theCommitsOwnDateDecides() {
        // 23:30 in Tokyo is still the 3rd there, even though it is the 2nd in UTC
        CommitInfo tokyoLate = new CommitInfo(
                "abc1234", "Kenji", "kenji@example.jp",
                LocalDateTime.parse("2026-08-03T23:30:00").atZone(ZoneId.of("Asia/Tokyo")),
                "late night", List.of());

        assertTrue(new CommitFilter(LocalDate.of(2026, 8, 3), null, null).matches(tokyoLate));
        assertFalse(new CommitFilter(LocalDate.of(2026, 8, 4), null, null).matches(tokyoLate));
    }

    private static CommitInfo commit(String name, String email, String isoDateTime) {
        return new CommitInfo(
                "deadbeef",
                name,
                email,
                LocalDateTime.parse(isoDateTime).atZone(ZoneId.of("America/Chicago")),
                "test commit",
                List.of());
    }
}
