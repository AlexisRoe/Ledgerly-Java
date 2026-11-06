package com.ledgerly.shared.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.shared.error.PayloadTooLarge;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class CappedInputStreamTest {

    @Test
    void allowsExactlyTheLimit() throws Exception {
        try (var in = new CappedInputStream(new ByteArrayInputStream(new byte[100]), 100)) {
            assertThat(in.readAllBytes()).hasSize(100);
        }
    }

    @Test
    void failsOneByteOverTheLimit() {
        var in = new CappedInputStream(new ByteArrayInputStream(new byte[101]), 100);
        assertThatThrownBy(in::readAllBytes).isInstanceOf(PayloadTooLarge.class);
    }

    @Test
    void countsSingleByteReadsToo() {
        var in = new CappedInputStream(new ByteArrayInputStream(new byte[3]), 2);
        assertThatThrownBy(() -> {
                    while (in.read() != -1) {}
                })
                .isInstanceOf(PayloadTooLarge.class);
    }
}
