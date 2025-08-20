package no.ssb.dlp.pseudo.service.security;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.runtime.context.scope.Refreshable;
import jakarta.inject.Singleton;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ConfigurationProperties("app-roles")
@Data
@Singleton
@Refreshable
public class DynamicRolesConfig {
    private List<String> trustedIssuers = new ArrayList<>();
    private List<String> users = new ArrayList<>();
    private List<String> admins = new ArrayList<>();

    private Optional<String> usersGroup = Optional.empty();
    private Optional<String> adminsGroup = Optional.empty();
}
