/** Events other modules (audit) may listen to. Carry ids only: never email addresses (ADR 0001). */
@NullMarked
@NamedInterface("events")
package com.ledgerly.teams.events;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
