package com.ledgerly.teams.events;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.id.UuidV7;
import java.time.Instant;
import java.util.UUID;

/** Everything that changes a team. Sealed, so listeners can switch over it exhaustively. Ids only (ADR 0001). */
public sealed interface TeamEvent
        permits TeamEvent.TeamCreated,
                TeamEvent.MemberInvited,
                TeamEvent.InvitationRevoked,
                TeamEvent.InvitationAccepted,
                TeamEvent.InvitationDeclined,
                TeamEvent.MemberRoleChanged,
                TeamEvent.MemberRemoved,
                TeamEvent.MemberLeft {

    /** Stable across re-delivery, so a listener can recognise an event it has already handled. */
    UUID eventId();

    TeamId teamId();

    /** The user who did it. */
    UserId actorId();

    Instant occurredAt();

    record TeamCreated(UUID eventId, TeamId teamId, UserId actorId, Instant occurredAt) implements TeamEvent {
        public TeamCreated(TeamId teamId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, occurredAt);
        }
    }

    record MemberInvited(
            UUID eventId, TeamId teamId, UserId actorId, UUID invitationId, String role, Instant occurredAt)
            implements TeamEvent {
        public MemberInvited(TeamId teamId, UserId actorId, UUID invitationId, String role, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, invitationId, role, occurredAt);
        }
    }

    record InvitationRevoked(UUID eventId, TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt)
            implements TeamEvent {
        public InvitationRevoked(TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, invitationId, occurredAt);
        }
    }

    record InvitationAccepted(UUID eventId, TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt)
            implements TeamEvent {
        public InvitationAccepted(TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, invitationId, occurredAt);
        }
    }

    record InvitationDeclined(UUID eventId, TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt)
            implements TeamEvent {
        public InvitationDeclined(TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, invitationId, occurredAt);
        }
    }

    record MemberRoleChanged(
            UUID eventId,
            TeamId teamId,
            UserId actorId,
            UserId memberId,
            String oldRole,
            String newRole,
            Instant occurredAt)
            implements TeamEvent {
        public MemberRoleChanged(
                TeamId teamId, UserId actorId, UserId memberId, String oldRole, String newRole, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, memberId, oldRole, newRole, occurredAt);
        }
    }

    record MemberRemoved(UUID eventId, TeamId teamId, UserId actorId, UserId memberId, Instant occurredAt)
            implements TeamEvent {
        public MemberRemoved(TeamId teamId, UserId actorId, UserId memberId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, memberId, occurredAt);
        }
    }

    record MemberLeft(UUID eventId, TeamId teamId, UserId actorId, Instant occurredAt) implements TeamEvent {
        public MemberLeft(TeamId teamId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, occurredAt);
        }
    }
}
