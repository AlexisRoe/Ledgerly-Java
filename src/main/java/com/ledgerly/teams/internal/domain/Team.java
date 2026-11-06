package com.ledgerly.teams.internal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    private UUID id;

    private String name;
    private Instant createdAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Team() {}

    public Team(String name, Instant now) {
        this.id = com.ledgerly.shared.id.UuidV7.generate();
        this.name = name.strip();
        this.createdAt = now;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }
}
