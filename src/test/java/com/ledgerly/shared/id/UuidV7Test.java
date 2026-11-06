package com.ledgerly.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void hasVersion7AndRfcVariant() {
        UUID id = UuidV7.generate();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheTimestamp() {
        UUID id = UuidV7.generate(1_700_000_000_000L);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void idsFromLaterMillisecondsSortAfterEarlierOnes() {
        List<UUID> ids = new ArrayList<>();
        for (long t = 1_700_000_000_000L; t < 1_700_000_000_050L; t++) {
            ids.add(UuidV7.generate(t));
        }
        // Unsigned comparison of the 128 bits equals the order in which they were created.
        assertThat(ids)
                .isSortedAccordingTo((a, b) -> a.getMostSignificantBits() == b.getMostSignificantBits()
                        ? Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits())
                        : Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits()));
    }

    @Test
    void expenseIdsUseIt() {
        assertThat(ExpenseId.random().value().version()).isEqualTo(7);
    }
}
