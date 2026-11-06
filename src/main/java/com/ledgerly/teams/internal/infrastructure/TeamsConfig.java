package com.ledgerly.teams.internal.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link TeamsProperties}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TeamsProperties.class)
class TeamsConfig {}
