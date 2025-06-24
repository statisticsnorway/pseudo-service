package no.ssb.dlp.pseudo.service.security;

import com.nimbusds.jwt.JWTClaimNames;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.config.TokenConfiguration;
import io.micronaut.security.token.config.TokenConfigurationProperties;
import no.ssb.dlp.pseudo.service.accessgroups.CloudIdentityService;
import no.ssb.dlp.pseudo.service.accessgroups.EntityKey;
import no.ssb.dlp.pseudo.service.accessgroups.Membership;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomRolesFinderTest {

    CloudIdentityService cloudIdentityService = mock(CloudIdentityService.class);
    StaticRolesConfig rolesConfig = mock(StaticRolesConfig.class);
    TokenConfiguration tokenConfig = new TokenConfigurationProperties();
    CustomRolesFinder sut = new CustomRolesFinder(tokenConfig, rolesConfig, cloudIdentityService);

    @Test
    void single_user_gets_no_roles() {
        final String email = "john.doe@ssb.no";
        assertIterableEquals(List.of(), sut.resolveRoles(Map.of(tokenConfig.getNameKey(), email)));
    }
    @Test
    void single_user_get_user_role() {
        final String email = "john.doe@ssb.no";
        when(rolesConfig.getUsers()).thenReturn(List.of(email));
        assertIterableEquals(List.of(PseudoServiceRole.USER), sut.resolveRoles(Map.of(tokenConfig.getNameKey(), email)));
    }

    @Test
    void single_user_get_admin_role() {
        final String email = "john.doe@ssb.no";
        when(rolesConfig.getAdmins()).thenReturn(List.of(email));
        assertIterableEquals(List.of(PseudoServiceRole.ADMIN), sut.resolveRoles(Map.of(tokenConfig.getNameKey(), email)));
    }

    @Test
    void single_user_get_multiple_roles() {
        final String email = "john.doe@ssb.no";
        when(rolesConfig.getUsers()).thenReturn(List.of(email));
        when(rolesConfig.getAdmins()).thenReturn(List.of(email));
        assertIterableEquals(List.of(PseudoServiceRole.ADMIN, PseudoServiceRole.USER), sut.resolveRoles(Map.of(tokenConfig.getNameKey(), email)));
    }

    @Test
    void group_membership_user_role() {
        final String email = "john.doe";
        final String user_group = "user-group@ssb.no";
        when(rolesConfig.getUsersGroup()).thenReturn(Optional.of(user_group));
        when(cloudIdentityService.listMembers(eq(user_group)))
                .thenReturn(List.of(new Membership("John Doe", new EntityKey(email, "ssb"))));
        assertIterableEquals(List.of(PseudoServiceRole.USER), sut.resolveRoles(Map.of(tokenConfig.getNameKey(), email)));
    }

    @Test
    void group_membership_admin_role() {
        final String email = "john.doe@ssb.no";
        final String user_group = "user-group@ssb.no";
        when(rolesConfig.getAdminsGroup()).thenReturn(Optional.of(user_group));
        when(cloudIdentityService.listMembers(eq(user_group)))
                .thenReturn(List.of(new Membership("John Doe", new EntityKey(email, "ssb"))));
        assertIterableEquals(List.of(PseudoServiceRole.ADMIN), sut.resolveRoles(Map.of(tokenConfig.getNameKey(), email)));
    }

    @Test
    void authenticated_user_gets_no_roles_when_issuer_not_trusted() {
        final String email = "john.doe@ssb.no";
        when(rolesConfig.getUsers()).thenReturn(List.of(SecurityRule.IS_AUTHENTICATED));
        assertIterableEquals(List.of(), sut.resolveRoles(Map.of(tokenConfig.getNameKey(), email)));
    }

    @Test
    void authenticated_user_get_user_role_when_issuer_is_trusted() {
        final String email = "john.doe@ssb.no";
        final String trusted_issuer = "some-issuer-url/auth/realm";
        when(rolesConfig.getUsers()).thenReturn(List.of(SecurityRule.IS_AUTHENTICATED));
        when(rolesConfig.getTrustedIssuers()).thenReturn(List.of(trusted_issuer));
        assertIterableEquals(List.of(PseudoServiceRole.USER),
                sut.resolveRoles(Map.of(tokenConfig.getNameKey(), email, JWTClaimNames.ISSUER, trusted_issuer)));
    }

    @Test
    void three_letter_user_get_user_role_when_issuer_is_trusted() {
        final String user = "john.doe";
        final String trusted_issuer = "some-issuer-url/auth/realm";
        TokenConfiguration tokenConfig = mock(TokenConfigurationProperties.class);

        when(rolesConfig.getUsers()).thenReturn(List.of("john.doe@ssb.no"));
        when(rolesConfig.getTrustedIssuers()).thenReturn(List.of(trusted_issuer));
        when(tokenConfig.getNameKey()).thenReturn("email");

        CustomRolesFinder finder = new CustomRolesFinder(tokenConfig, this.rolesConfig, this.cloudIdentityService);
        assertIterableEquals(List.of(PseudoServiceRole.USER),
                finder.resolveRoles(Map.of("sub", user, JWTClaimNames.ISSUER, trusted_issuer)));
    }
}