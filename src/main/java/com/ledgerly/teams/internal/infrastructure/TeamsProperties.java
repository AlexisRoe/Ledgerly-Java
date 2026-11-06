package com.ledgerly.teams.internal.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** {@code ledgerly.teams.invitation-ttl}: how long an invitation can be answered (default 7 days). */
@Validated
@ConfigurationProperties("ledgerly.teams")
public record TeamsProperties(@DefaultValue("7d") Duration invitationTtl) {}
