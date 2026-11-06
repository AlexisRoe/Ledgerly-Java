package com.ledgerly;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The temporary debug adapters must never exist outside local and test. */
class ProdProfileTest {

    private ApplicationContextRunner runner(String profile) throws Exception {
        Class<?> adapter = Class.forName("com.ledgerly.expenses.internal.infrastructure.DebugHeaderMembershipLookup");
        Constructor<?> ctor = adapter.getDeclaredConstructor();
        ctor.setAccessible(true);
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profile))
                .withUserConfiguration(adapter);
    }

    private static Class<?> port() throws Exception {
        return Class.forName("com.ledgerly.expenses.internal.application.MembershipLookup");
    }

    @Test
    void prodHasNoDebugMembershipLookup() throws Exception {
        runner("prod").run(ctx -> assertThat(ctx).doesNotHaveBean(port()));
    }

    @Test
    void localAndTestDo() throws Exception {
        runner("local").run(ctx -> assertThat(ctx).hasSingleBean(port()));
        runner("test").run(ctx -> assertThat(ctx).hasSingleBean(port()));
    }
}
