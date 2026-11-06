package com.ledgerly.owasp;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerly.owasp.archfixture.OutboundCallsFromTheWrongPlace;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import javax.net.ssl.HttpsURLConnection;
import org.junit.jupiter.api.Test;

/**
 * API7: Server-side request forgery. Controls: no feature fetches a URL a user supplied; outbound calls exist only
 * towards the object store and the malware scanner, and only in infrastructure code. Proof: an ArchUnit rule that
 * only {@code infrastructure} packages may use HTTP or socket clients. (The rule is checked on production classes;
 * the second test shows that it really fails when a class breaks it.)
 */
@AnalyzeClasses(packages = "com.ledgerly", importOptions = ImportOption.DoNotIncludeTests.class)
class Api7ServerSideRequestForgeryTest {

    /** Packages that are allowed to talk to other systems: adapters, and the shared object-store adapter. */
    static final String[] ADAPTER_PACKAGES = {"..infrastructure..", "..shared.storage.."};

    static final ArchRule ONLY_INFRASTRUCTURE_MAY_CALL_OUT = noClasses()
            .that()
            .resideOutsideOfPackages(ADAPTER_PACKAGES)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "java.net.http..",
                    "org.springframework.web.client..",
                    "org.springframework.web.reactive.function.client..",
                    "org.springframework.http.client..",
                    "org.apache.hc..",
                    "okhttp3..",
                    "software.amazon.awssdk..")
            .orShould()
            .dependOnClassesThat(belongToAnyOf(
                    Socket.class, URL.class, URLConnection.class, HttpURLConnection.class, HttpsURLConnection.class))
            .as("only infrastructure packages may use HTTP or socket clients");

    @ArchTest
    static final ArchRule onlyInfrastructureMayCallOut = ONLY_INFRASTRUCTURE_MAY_CALL_OUT;

    @Test
    void theRuleReallyFailsForAClassThatBreaksIt() {
        var violators = new ClassFileImporter().importClasses(OutboundCallsFromTheWrongPlace.class);
        assertThatThrownBy(() -> ONLY_INFRASTRUCTURE_MAY_CALL_OUT.check(violators))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("only infrastructure packages may use HTTP or socket clients");
    }

    @Test
    void theSameCodeInAnInfrastructurePackageIsAllowed() {
        // The adapters themselves do use these classes, so the rule is not simply "never".
        var production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ledgerly.receipts.internal.infrastructure", "com.ledgerly.shared.storage");
        assertThat(production.stream().anyMatch(c -> c.getSimpleName().equals("ClamAvScanner")))
                .isTrue();
        // The adapters use these classes for real; the rule exempts them (so nothing is left for it to check there).
        assertThat(production
                        .get("com.ledgerly.receipts.internal.infrastructure.ClamAvScanner")
                        .getDirectDependenciesFromSelf())
                .anyMatch(d -> d.getTargetClass().isEquivalentTo(Socket.class));
        ONLY_INFRASTRUCTURE_MAY_CALL_OUT.allowEmptyShould(true).check(production);
    }
}
