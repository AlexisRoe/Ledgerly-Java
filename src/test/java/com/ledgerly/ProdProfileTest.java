package com.ledgerly;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.shared.security.CurrentUser;
import com.ledgerly.shared.security.DebugHeaderCurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The debug-header adapters must never exist outside local and test. */
class ProdProfileTest {

    private ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(profile))
                .withUserConfiguration(DebugHeaderCurrentUser.class);
    }

    @Test
    void prodHasNoDebugCurrentUser() {
        runner("prod").run(ctx -> assertThat(ctx).doesNotHaveBean(CurrentUser.class));
    }

    @Test
    void localAndTestDo() {
        runner("local").run(ctx -> assertThat(ctx).hasSingleBean(CurrentUser.class));
        runner("test").run(ctx -> assertThat(ctx).hasSingleBean(CurrentUser.class));
    }
}
