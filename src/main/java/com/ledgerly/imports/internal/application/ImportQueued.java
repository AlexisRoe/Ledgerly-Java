package com.ledgerly.imports.internal.application;

import java.util.UUID;

/** Internal to imports: the row is saved, the job may start now (after the transaction commits). */
public record ImportQueued(UUID importId) {}
