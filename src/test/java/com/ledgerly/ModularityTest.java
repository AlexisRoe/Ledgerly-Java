package com.ledgerly;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(LedgerlyApplication.class).verify();
    }
}
