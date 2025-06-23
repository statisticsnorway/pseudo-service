package no.ssb.dlp.pseudo.service.security;

import com.nimbusds.jwt.JWTClaimNames;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requirements;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.DefaultRolesFinder;
import io.micronaut.security.token.RolesFinder;
import io.micronaut.security.token.config.TokenConfiguration;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.ssb.dlp.pseudo.service.accessgroups.CloudIdentityService;
import no.ssb.dlp.pseudo.service.accessgroups.Membership;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Singleton
@Replaces(bean = DefaultRolesFinder.class)
@RequiredArgsConstructor
@Requirements({
        @Requires(notEnv = Environment.TEST),
        @Requires(notEquals = "endpoints.cloud-run.enabled", value = "true")
})
@Slf4j
public class CustomRolesFinder implements RolesFinder {

    private final TokenConfiguration tokenConfiguration;
    private final StaticRolesConfig rolesConfig;
    private final CloudIdentityService cloudIdentityService;

    @Override
    public List<String> resolveRoles(Map<String, Object> attributes) {
        List<String> roles = new ArrayList<>();

        Object username;
        switch (attributes.get(tokenConfiguration.getNameKey())) { // Use the "sub" claim if the "email" claim is not present in token
            case null -> username = attributes.get("sub");
            case Object obj -> username = obj.toString().replace("@ssb.no", "");
        }
        
        boolean trustedIssuer = isTrustedIssuer(attributes);
        log.debug("User {} has a trusted issuer? {}", username, trustedIssuer);
        if (rolesConfig.getAdmins().contains(SecurityRule.IS_AUTHENTICATED) && trustedIssuer
                || rolesConfig.getAdmins().contains(username)) {
            roles.add(PseudoServiceRole.ADMIN);
        }
        if (rolesConfig.getUsers().contains(SecurityRule.IS_AUTHENTICATED) && trustedIssuer
                || rolesConfig.getUsers().contains(username)) {
            roles.add(PseudoServiceRole.USER);
        }
        if (rolesConfig.getAdminsGroup().isPresent()) {
            final List<Membership> adminMembers = cloudIdentityService.listMembers(rolesConfig.getAdminsGroup().get());
            if (adminMembers.stream().anyMatch(value -> value.preferredMemberKey().id().equals(username))) {
                roles.add(PseudoServiceRole.ADMIN);
            }
        }
        if (rolesConfig.getUsersGroup().isPresent()) {
            final List<Membership> adminMembers = cloudIdentityService.listMembers(rolesConfig.getUsersGroup().get());
            if (adminMembers.stream().anyMatch(value -> value.preferredMemberKey().id().equals(username))) {
                roles.add(PseudoServiceRole.USER);
            }
        }
        if (roles.isEmpty()) {
            log.info("Could not resolve any roles for user {}", username);
        }
        log.debug("Resolved roles {} for user {}", roles, username);
        return roles;
    }

    private boolean isTrustedIssuer(Map<String, Object> attributes) {
        return rolesConfig.getTrustedIssuers().contains(String.valueOf(attributes.get(JWTClaimNames.ISSUER)));
    }
}
