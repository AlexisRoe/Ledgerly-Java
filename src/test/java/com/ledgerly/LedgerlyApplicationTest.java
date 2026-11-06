package com.ledgerly;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.shared.config.LedgerlyLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ExtendWith(OutputCaptureExtension.class)
class LedgerlyApplicationTest {

    @Autowired
    LedgerlyLimits limits;

    @Test
    void contextLoadsWithDefaultLimits() {
        assertThat(limits.maxPageSize()).isEqualTo(100);
        assertThat(limits.maxBatchSize()).isEqualTo(100);
        assertThat(limits.maxUploadSize()).isEqualTo(DataSize.ofMegabytes(10));
        assertThat(limits.maxImportRows()).isEqualTo(10_000);
        assertThat(limits.maxImportsPerDay()).isEqualTo(5);
    }

    @Test
    void logLinesAreJson(CapturedOutput output) {
        org.slf4j.LoggerFactory.getLogger("probe").info("hello structured logging");
        String line = output.getAll()
                .lines()
                .filter(l -> l.contains("hello structured logging"))
                .findFirst()
                .orElseThrow();
        var node = JsonMapper.builder().build().readTree(line);
        assertThat(node.get("message").asString()).isEqualTo("hello structured logging");
    }
}
