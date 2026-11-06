package com.ledgerly.receipts.internal.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReceiptsProperties.class)
@EnableAsync
@EnableResilientMethods
class ReceiptsConfig {}
