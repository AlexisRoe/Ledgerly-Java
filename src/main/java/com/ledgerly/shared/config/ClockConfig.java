package com.ledgerly.shared.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one source of "now" (UTC). Code takes a Clock so tests can substitute a fixed or movable one. */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    /** System UTC clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
