package com.ledgerly.teams.events;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.time.Instant;

/** Everything that changes a team. Sealed, so listeners can switch over it exhaustively. */
public sealed interface TeamEvent
        permits TeamEvent.TeamCreated,
                TeamEvent.MemberInvited,
                TeamEvent.InvitationRevoked,
                TeamEvent.InvitationAccepted,
                TeamEvent.InvitationDeclined,
                TeamEvent.MemberRoleChanged,
                TeamEvent.MemberRemoved,
                TeamEvent.MemberLeft {

    TeamId teamId();

    /** The user who did it. */
    UserId actorId();

    Instant occurredAt();

    record TeamCreated(TeamId teamId, UserId actorId, Instant occurredAt) implements TeamEvent {}

    record MemberInvited(TeamId teamId, UserId actorId, java.util.UUID invitationId, String role, Instant occurredAt)
            implements TeamEvent {}

    record InvitationRevoked(TeamId teamId, UserId actorId, java.util.UUID invitationId, Instant occurredAt)
            implements TeamEvent {}

    record InvitationAccepted(TeamId teamId, UserId actorId, java.util.UUID invitationId, Instant occurredAt)
            implements TeamEvent {}

    record InvitationDeclined(TeamId teamId, UserId actorId, java.util.UUID invitationId, Instant occurredAt)
            implements TeamEvent {}

    record MemberRoleChanged(
            TeamId teamId, UserId actorId, UserId memberId, String oldRole, String newRole, Instant occurredAt)
            implements TeamEvent {}

    record MemberRemoved(TeamId teamId, UserId actorId, UserId memberId, Instant occurredAt) implements TeamEvent {}

    record MemberLeft(TeamId teamId, UserId actorId, Instant occurredAt) implements TeamEvent {}
}
