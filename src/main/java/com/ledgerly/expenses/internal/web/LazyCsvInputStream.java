package com.ledgerly.expenses.internal.web;

import com.ledgerly.expenses.internal.application.ExpenseView;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * Turns pages of expenses into CSV bytes as the response is written, so memory use stays at about one page no
 * matter how many rows are exported.
 */
final class LazyCsvInputStream extends InputStream {

    static final String HEADER = CsvCells.row(
            "id", "incurredOn", "merchant", "amount", "currency", "category", "status", "claimantId", "note");

    private final Supplier<List<ExpenseView>> pages;
    private byte[] buffer = HEADER.getBytes(StandardCharsets.UTF_8);
    private int position;
    private boolean finished;

    LazyCsvInputStream(Supplier<List<ExpenseView>> pages) {
        this.pages = pages;
    }

    @Override
    public int read() {
        if (!ensureData()) {
            return -1;
        }
        return buffer[position++] & 0xFF;
    }

    @Override
    public int read(byte[] target, int off, int len) {
        if (len == 0) {
            return 0;
        }
        if (!ensureData()) {
            return -1;
        }
        int n = Math.min(len, buffer.length - position);
        System.arraycopy(buffer, position, target, off, n);
        position += n;
        return n;
    }

    private boolean ensureData() {
        while (position >= buffer.length) {
            if (finished) {
                return false;
            }
            List<ExpenseView> page = pages.get();
            if (page.isEmpty()) {
                finished = true;
                return false;
            }
            var text = new StringBuilder();
            for (ExpenseView e : page) {
                text.append(CsvCells.row(
                        e.id().toString(),
                        e.incurredOn().toString(),
                        e.merchant(),
                        e.money().amount().toPlainString(),
                        e.money().currency().getCurrencyCode(),
                        e.category().name(),
                        e.status().name(),
                        e.claimantId().toString(),
                        e.note() == null ? "" : e.note()));
            }
            buffer = text.toString().getBytes(StandardCharsets.UTF_8);
            position = 0;
        }
        return true;
    }
}
