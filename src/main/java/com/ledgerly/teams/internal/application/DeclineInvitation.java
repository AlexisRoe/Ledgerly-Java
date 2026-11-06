package com.ledgerly.teams.internal.application;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.teams.events.TeamEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeclineInvitation {

    private final InvitationResponses responses;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DeclineInvitation(InvitationResponses responses, ApplicationEventPublisher events, Clock clock) {
        this.responses = responses;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void execute(UUID invitationId, UserId caller) {
        var invitation = responses.load(invitationId, caller).invitation();
        Instant now = clock.instant();
        responses.requirePending(invitation, now);
        invitation.decline();
        events.publishEvent(
                new TeamEvent.InvitationDeclined(new TeamId(invitation.teamId()), caller, invitationId, now));
    }
}
