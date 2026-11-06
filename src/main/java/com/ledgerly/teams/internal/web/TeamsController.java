package com.ledgerly.teams.internal.web;

import com.ledgerly.shared.api.MeApi;
import com.ledgerly.shared.api.TeamsApi;
import com.ledgerly.shared.api.model.ChangeRoleRequest;
import com.ledgerly.shared.api.model.CreateTeamRequest;
import com.ledgerly.shared.api.model.InvitationResponse;
import com.ledgerly.shared.api.model.InvitationStatus;
import com.ledgerly.shared.api.model.InviteRequest;
import com.ledgerly.shared.api.model.MeInvitation;
import com.ledgerly.shared.api.model.MeResponse;
import com.ledgerly.shared.api.model.MeTeam;
import com.ledgerly.shared.api.model.MemberResponse;
import com.ledgerly.shared.api.model.MembershipResponse;
import com.ledgerly.shared.api.model.Role;
import com.ledgerly.shared.api.model.TeamResponse;
import com.ledgerly.shared.security.CurrentUser;
import com.ledgerly.teams.internal.application.AcceptInvitation;
import com.ledgerly.teams.internal.application.ChangeMemberRole;
import com.ledgerly.teams.internal.application.CreateTeam;
import com.ledgerly.teams.internal.application.DeclineInvitation;
import com.ledgerly.teams.internal.application.GetMe;
import com.ledgerly.teams.internal.application.InvitationView;
import com.ledgerly.teams.internal.application.InviteMember;
import com.ledgerly.teams.internal.application.LeaveTeam;
import com.ledgerly.teams.internal.application.RemoveMember;
import com.ledgerly.teams.internal.application.RevokeInvitation;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Team-scoped operations are guarded by {@code @PreAuthorize} with {@code TeamAuthorization}: not a teammate is
 * 404, a teammate with the wrong role is 403. The use cases re-check inside their transaction, because a role can
 * change between this check and the work.
 */
@RestController
@RequestMapping("/api/v1")
class TeamsController implements TeamsApi, MeApi {

    private final CurrentUser currentUser;
    private final CreateTeam createTeam;
    private final InviteMember inviteMember;
    private final RevokeInvitation revokeInvitation;
    private final AcceptInvitation acceptInvitation;
    private final DeclineInvitation declineInvitation;
    private final ChangeMemberRole changeMemberRole;
    private final RemoveMember removeMember;
    private final LeaveTeam leaveTeam;
    private final GetMe getMe;

    TeamsController(
            CurrentUser currentUser,
            CreateTeam createTeam,
            InviteMember inviteMember,
            RevokeInvitation revokeInvitation,
            AcceptInvitation acceptInvitation,
            DeclineInvitation declineInvitation,
            ChangeMemberRole changeMemberRole,
            RemoveMember removeMember,
            LeaveTeam leaveTeam,
            GetMe getMe) {
        this.currentUser = currentUser;
        this.createTeam = createTeam;
        this.inviteMember = inviteMember;
        this.revokeInvitation = revokeInvitation;
        this.acceptInvitation = acceptInvitation;
        this.declineInvitation = declineInvitation;
        this.changeMemberRole = changeMemberRole;
        this.removeMember = removeMember;
        this.leaveTeam = leaveTeam;
        this.getMe = getMe;
    }

    @Override
    public ResponseEntity<TeamResponse> createTeam(CreateTeamRequest request) {
        var result = createTeam.execute(request.getName(), currentUser.id());
        return ResponseEntity.created(URI.create("/api/v1/teams/" + result.id()))
                .body(new TeamResponse(result.id(), result.name()));
    }

    @Override
    @PreAuthorize("@teamAuthorization.anyOf(#teamId, 'OWNER')")
    public ResponseEntity<InvitationResponse> inviteMember(UUID teamId, InviteRequest request) {
        InvitationView view =
                inviteMember.execute(teamId, currentUser.id(), request.getEmail(), toDomain(request.getRole()));
        return ResponseEntity.status(201)
                .body(new InvitationResponse(
                        view.id(),
                        toApi(view.role()),
                        InvitationStatus.valueOf(view.status().name()),
                        view.expiresAt().atOffset(ZoneOffset.UTC)));
    }

    @Override
    @PreAuthorize("@teamAuthorization.anyOf(#teamId, 'OWNER')")
    public ResponseEntity<Void> revokeInvitation(UUID teamId, UUID invitationId) {
        revokeInvitation.execute(teamId, invitationId, currentUser.id());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<MembershipResponse> acceptInvitation(UUID invitationId) {
        var result = acceptInvitation.execute(invitationId, currentUser.id());
        return ResponseEntity.ok(new MembershipResponse(result.teamId(), toApi(result.role())));
    }

    @Override
    public ResponseEntity<Void> declineInvitation(UUID invitationId) {
        declineInvitation.execute(invitationId, currentUser.id());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("@teamAuthorization.anyOf(#teamId, 'OWNER')")
    public ResponseEntity<MemberResponse> changeMemberRole(UUID teamId, UUID userId, ChangeRoleRequest request) {
        var result = changeMemberRole.execute(teamId, currentUser.id(), userId, toDomain(request.getRole()));
        return ResponseEntity.ok(new MemberResponse(result.userId(), toApi(result.role())));
    }

    @Override
    @PreAuthorize("@teamAuthorization.anyOf(#teamId, 'OWNER')")
    public ResponseEntity<Void> removeMember(UUID teamId, UUID userId) {
        removeMember.execute(teamId, currentUser.id(), userId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("@teamAuthorization.atLeast(#teamId, 'MEMBER')")
    public ResponseEntity<Void> leaveTeam(UUID teamId) {
        leaveTeam.execute(teamId, currentUser.id());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<MeResponse> getMe() {
        var me = getMe.execute(currentUser.id());
        return ResponseEntity.ok(new MeResponse(
                me.id(),
                me.email(),
                me.verified(),
                me.teams().stream()
                        .map(t -> new MeTeam(t.teamId(), t.name(), toApi(t.role())))
                        .toList(),
                me.invitations().stream()
                        .map(i -> new MeInvitation(
                                i.id(),
                                i.teamId(),
                                i.teamName(),
                                toApi(i.role()),
                                i.expiresAt().atOffset(ZoneOffset.UTC)))
                        .toList()));
    }

    private static com.ledgerly.shared.role.Role toDomain(Role role) {
        return com.ledgerly.shared.role.Role.valueOf(role.name());
    }

    private static Role toApi(com.ledgerly.shared.role.Role role) {
        return Role.valueOf(role.name());
    }
}
