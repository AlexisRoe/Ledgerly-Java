package com.ledgerly;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Writes the module diagrams and canvases to {@code docs/modules/}, so the picture of the application is generated
 * from the code and cannot drift from it. Commit what it writes.
 */
class ModuleDocumentationTest {

    @Test
    void writesDiagramsAndCanvases() throws Exception {
        var modules = ApplicationModules.of(LedgerlyApplication.class);
        new Documenter(modules, Documenter.Options.defaults().withOutputFolder("docs/modules")).writeDocumentation();

        Path folder = Path.of("docs/modules");
        assertThat(folder.resolve("components.puml")).exists();
        try (var files = Files.list(folder)) {
            assertThat(files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("module-")))
                    .anyMatch(n -> n.contains("audit"))
                    .anyMatch(n -> n.contains("expenses"));
        }
    }
}
