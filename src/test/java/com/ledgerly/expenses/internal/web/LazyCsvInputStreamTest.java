package com.ledgerly.expenses.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.expenses.Category;
import com.ledgerly.expenses.internal.application.ExpenseView;
import com.ledgerly.expenses.internal.domain.ExpenseStatus;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.money.Money;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The CSV export is streamed page by page: what it must contain, when it asks for more, and what it must not emit. */
class LazyCsvInputStreamTest {

    static ExpenseView expense(String merchant, String note) {
        return new ExpenseView(
                ExpenseId.random(),
                TeamId.random(),
                UserId.random(),
                Money.of("12.50", "EUR"),
                Category.MEALS,
                LocalDate.parse("2026-01-15"),
                merchant,
                note,
                ExpenseStatus.DRAFT,
                List.of(),
                0);
    }

    static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Hands out the given pages, then empty lists; counts how often it was asked. */
    static class Pages implements java.util.function.Supplier<List<ExpenseView>> {
        final Deque<List<ExpenseView>> queue = new ArrayDeque<>();
        final AtomicInteger asked = new AtomicInteger();

        @SafeVarargs
        Pages(List<ExpenseView>... pages) {
            queue.addAll(List.of(pages));
        }

        @Override
        public List<ExpenseView> get() {
            asked.incrementAndGet();
            return queue.isEmpty() ? List.of() : queue.poll();
        }
    }

    @Test
    void anExportWithoutRowsIsJustTheHeaderLine() throws Exception {
        var pages = new Pages();
        String csv = readAll(new LazyCsvInputStream(pages));
        assertThat(csv).isEqualTo(LazyCsvInputStream.HEADER);
        assertThat(csv.lines()).hasSize(1);
    }

    @Test
    void nothingIsFetchedBeforeTheHeaderHasBeenRead() throws Exception {
        var pages = new Pages(List.of(expense("A", null)));
        var in = new LazyCsvInputStream(pages);

        byte[] header = in.readNBytes(LazyCsvInputStream.HEADER.length());

        assertThat(new String(header, StandardCharsets.UTF_8)).isEqualTo(LazyCsvInputStream.HEADER);
        assertThat(pages.asked).hasValue(0);
    }

    @Test
    void rowsOfAllPagesComeInOrderAndAnEmptyPageEndsTheStreamForGood() throws Exception {
        var first = expense("First", null);
        var second = expense("Second", null);
        var pages = new Pages(List.of(first), List.of(second));
        var in = new LazyCsvInputStream(pages);

        String csv = readAll(in);

        assertThat(csv.lines().toList()).hasSize(3);
        assertThat(csv.indexOf("First")).isLessThan(csv.indexOf("Second"));
        assertThat(in.read()).isEqualTo(-1);
        assertThat(in.read(new byte[8], 0, 8)).isEqualTo(-1);
        assertThat(pages.asked)
                .as("two pages and the empty one; never asked again after the end")
                .hasValue(3);
    }

    @Test
    void readingByteByByteAndInBulkGiveTheSameBytes() throws Exception {
        List<ExpenseView> rows = List.of(expense("Cafe, \"Central\"", "line one\nline two"), expense("B", null));
        var bulk = new LazyCsvInputStream(new Pages(rows));
        var single = new LazyCsvInputStream(new Pages(rows));

        var singleBytes = new ByteArrayOutputStream();
        for (int b = single.read(); b != -1; b = single.read()) {
            singleBytes.write(b);
        }
        var bulkBytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[7]; // smaller than a row, so rows are split across reads
        for (int n = bulk.read(chunk, 0, chunk.length); n != -1; n = bulk.read(chunk, 0, chunk.length)) {
            bulkBytes.write(chunk, 0, n);
        }

        assertThat(bulkBytes.toByteArray()).isEqualTo(singleBytes.toByteArray());
    }

    @Test
    void aMerchantThatLooksLikeAFormulaIsNeverEmittedAsOne() throws Exception {
        var pages = new Pages(List.of(expense("=HYPERLINK(\"http://evil\",\"x\")", "+cmd|' /C calc'!A0")));

        List<String> lines = readAll(new LazyCsvInputStream(pages)).lines().toList();

        String row = lines.get(1);
        assertThat(row).doesNotContain(",=").doesNotContain(",+");
        assertThat(row).contains("'=HYPERLINK").contains("'+cmd");
    }

    @Test
    void aNoteWithCommasQuotesAndLineBreaksStaysOneCell() throws Exception {
        var pages = new Pages(List.of(expense("Shop", "he said \"no\", twice\nthen left")));

        String csv = readAll(new LazyCsvInputStream(pages));

        assertThat(csv).contains("\"he said \"\"no\"\", twice\nthen left\"");
    }

    @Test
    void aMissingNoteIsAnEmptyLastCell() throws Exception {
        var pages = new Pages(List.of(expense("Shop", null)));

        List<String> lines = readAll(new LazyCsvInputStream(pages)).lines().toList();

        assertThat(lines.get(1)).endsWith(",");
    }

    @Test
    void aZeroLengthReadReturnsZeroWithoutConsumingAnything() throws Exception {
        var in = new LazyCsvInputStream(new Pages());
        assertThat(in.read(new byte[4], 0, 0)).isZero();
        assertThat(readAll(in)).isEqualTo(LazyCsvInputStream.HEADER);
    }

    @Test
    void theRowCountMatchesWhatWasHandedOut() throws Exception {
        List<ExpenseView> many = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            many.add(expense("M" + i, null));
        }
        var pages = new Pages(many.subList(0, 500), many.subList(500, 1000), many.subList(1000, 1200));

        assertThat(readAll(new LazyCsvInputStream(pages)).lines().count()).isEqualTo(1201);
    }
}
