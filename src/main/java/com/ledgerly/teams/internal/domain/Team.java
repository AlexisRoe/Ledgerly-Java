package com.ledgerly.teams.internal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A group of people sharing expenses. Holds only its name; who belongs is in {@link Membership}. */
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

    /** @param now creation time */
    public Team(String name, Instant now) {
        this.id = com.ledgerly.shared.id.UuidV7.generate();
        this.name = name.strip();
        this.createdAt = now;
    }

    /** The team's id (UUIDv7). */
    public UUID id() {
        return id;
    }

    /** The display name. */
    public String name() {
        return name;
    }
}
