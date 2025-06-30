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

import java.util.*;

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
        boolean trustedIssuer = isTrustedIssuer(attributes);

        Optional<String> email;
        if (attributes.get(tokenConfiguration.getNameKey()) == null && trustedIssuer) { // Expects three-letter initials only in "sub" claim
            email = Optional.ofNullable(Objects.toString(attributes.get("sub"), null)).map(v -> v.concat("@ssb.no"));
        }
        else {
            email = Optional.ofNullable(Objects.toString(attributes.get(tokenConfiguration.getNameKey()), null));
        }

        log.info("User {} has a trusted issuer? {}", email, trustedIssuer);

        // We check for trustedIssuer when in environments where all authenticated requests are accepted
        // This is due to Google tokens being valid for authorization purposes,
        // however they get no roles since they are not a trusted issuer.

        if (rolesConfig.getAdmins().contains(SecurityRule.IS_AUTHENTICATED) && trustedIssuer
                || email.map(rolesConfig.getAdmins()::contains).orElse(false)) {
            roles.add(PseudoServiceRole.ADMIN);
        }

        if (rolesConfig.getUsers().contains(SecurityRule.IS_AUTHENTICATED) && trustedIssuer
                || email.map(rolesConfig.getUsers()::contains).orElse(false)) {
            roles.add(PseudoServiceRole.USER);
        }
        if (rolesConfig.getAdminsGroup().isPresent()) {
            final List<Membership> adminMembers = cloudIdentityService.listMembers(rolesConfig.getAdminsGroup().get());
            if (email.map(user_email -> adminMembers.stream().anyMatch(value -> value.preferredMemberKey().id().equals(user_email))).orElse(false) ) {
                roles.add(PseudoServiceRole.ADMIN);
            }
        }
        if (rolesConfig.getUsersGroup().isPresent()) {
            final List<Membership> userMembers = cloudIdentityService.listMembers(rolesConfig.getUsersGroup().get());
            List<String> userEmails = cloudIdentityService.listMembers(rolesConfig.getUsersGroup().get()).stream().map(v -> v.preferredMemberKey().id()).toList();
            log.info("User group {} has members {}", rolesConfig.getUsersGroup().get(), userEmails);
            if (email.map(user_email -> userMembers.stream().anyMatch(value -> value.preferredMemberKey().id().equals(user_email))).orElse(false)) {
                roles.add(PseudoServiceRole.USER);
            }
        }
        if (roles.isEmpty()) {
            log.info("Could not resolve any roles for user {}", email);
        }
        log.info("Resolved roles {} for user {}", roles, email);
        return roles;
    }

    private boolean isTrustedIssuer(Map<String, Object> attributes) {
        return rolesConfig.getTrustedIssuers().contains(String.valueOf(attributes.get(JWTClaimNames.ISSUER)));
    }
}
