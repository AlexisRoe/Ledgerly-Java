package com.ledgerly.shared.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registered from {@code shared} so that every module, including one started alone in a test, can read the limits. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerlyLimits.class)
class LimitsConfig {}
