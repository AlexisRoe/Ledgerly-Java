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

    /** The team the event happened in. */
    TeamId teamId();

    /** The user who did it. */
    UserId actorId();

    /** When it happened (application clock). */
    Instant occurredAt();

    /** A team was created; its creator became OWNER. */
    record TeamCreated(UUID eventId, TeamId teamId, UserId actorId, Instant occurredAt) implements TeamEvent {
        public TeamCreated(TeamId teamId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, occurredAt);
        }
    }

    /** An OWNER invited an email address. {@code role} is the role offered (a name, no email is carried). */
    record MemberInvited(
            UUID eventId, TeamId teamId, UserId actorId, UUID invitationId, String role, Instant occurredAt)
            implements TeamEvent {
        public MemberInvited(TeamId teamId, UserId actorId, UUID invitationId, String role, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, invitationId, role, occurredAt);
        }
    }

    /** An OWNER withdrew a pending invitation. */
    record InvitationRevoked(UUID eventId, TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt)
            implements TeamEvent {
        public InvitationRevoked(TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, invitationId, occurredAt);
        }
    }

    /** The invited person accepted and became a member. */
    record InvitationAccepted(UUID eventId, TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt)
            implements TeamEvent {
        public InvitationAccepted(TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, invitationId, occurredAt);
        }
    }

    /** The invited person declined. */
    record InvitationDeclined(UUID eventId, TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt)
            implements TeamEvent {
        public InvitationDeclined(TeamId teamId, UserId actorId, UUID invitationId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, invitationId, occurredAt);
        }
    }

    /** An OWNER changed a member's role; old and new are role names. */
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

    /** An OWNER removed {@code memberId} from the team. */
    record MemberRemoved(UUID eventId, TeamId teamId, UserId actorId, UserId memberId, Instant occurredAt)
            implements TeamEvent {
        public MemberRemoved(TeamId teamId, UserId actorId, UserId memberId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, memberId, occurredAt);
        }
    }

    /** A member left the team on their own. */
    record MemberLeft(UUID eventId, TeamId teamId, UserId actorId, Instant occurredAt) implements TeamEvent {
        public MemberLeft(TeamId teamId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, actorId, occurredAt);
        }
    }
}
