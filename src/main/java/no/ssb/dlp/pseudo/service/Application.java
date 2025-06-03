package no.ssb.dlp.pseudo.service;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@OpenAPIDefinition(
        info = @Info(
                title = "Dapla Pseudo Service",
                version = "1.0",
                description = "Endpoints that support pseudonymization and depseudonymization of data and datasets",
                contact = @Contact(
                        url = "https://statistics-norway.atlassian.net/wiki/spaces/PSEUDO",
                        name = "Team Statistikktjenester",
                        email = "team-statistikktjenester@ssb.no"
                )
        ),
        security = @SecurityRequirement(name = "Keycloak token"),
        servers = {
                @Server(url = "https://pseudo-service.test.ssb.no", description = "Test"),
                @Server(url = "http://localhost:10210", description = "Locally proxied pseudo service")
        }
)
@SecurityScheme(
        name = "Keycloak token",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "jwt",
        in = SecuritySchemeIn.HEADER,
        paramName = "Authorization"
)
@Slf4j
public class Application {
    @Getter
    private static ApplicationContext context;

    public static void main(String[] args) {
        log.info("pseudo-service version: {}", BuildInfo.INSTANCE.getVersionAndBuildTimestamp());
        context = Micronaut.run(Application.class, args);
    }
}