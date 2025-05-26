package no.ssb.dlp.pseudo.service.sid;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.generator.TokenGenerator;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import no.ssb.dlp.pseudo.service.security.PseudoServiceRole;
import no.ssb.dlp.pseudo.service.sid.local.SidCache;
import no.ssb.dlp.pseudo.service.sid.local.SidReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;


import java.util.Optional;
import java.util.Set;

import static org.hamcrest.CoreMatchers.*;


/**
 * Test of {@link SidLookupController} by configuring a {@link LocalSidServiceController} to simulate an external
 * SID service. This also tests that the bearer token is propagated from the {@link SidLookupController} to the
 * {@link LocalSidServiceController} since both controllers require authentication.
 */
@MicronautTest(environments = "local-sid")
public class SidLookupControllerE2ETest {

    @Inject
    TokenGenerator tokenGenerator;
    @Inject
    SidCache sidCache;
    @Inject
    SidReader sidReader;
    @Inject
    @Client("/")
    HttpClient client;

    @BeforeEach
    public void setUp() {
        if (sidCache.getState() == SidCache.State.NOT_INITIALIZED) {
            sidReader.readSidsFromFile("src/test/resources/freg/snr-kat-sample", sidCache);
        }
    }
    @Test
    public void testLookupFnr(RequestSpecification spec) {
        Authentication user = Authentication.build("sherlock", Set.of(PseudoServiceRole.ADMIN));
        Optional<String> accessToken = tokenGenerator.generateToken(user, 10000);
        Assertions.assertTrue(accessToken.isPresent());

        spec.when().auth().oauth2(accessToken.get())
            .get("/sid/fnr/{fnr}", "11854898347")
        .then()
            .statusCode(200)
            .body(containsString("11854898347"))
            .body(containsString("0001ha3"));
    }
    @Test
    public void testLookupSnr(RequestSpecification spec) {
        Authentication user = Authentication.build("sherlock", Set.of(PseudoServiceRole.ADMIN));
        Optional<String> accessToken = tokenGenerator.generateToken(user, 10000);
        Assertions.assertTrue(accessToken.isPresent());

        spec.when().auth().oauth2(accessToken.get())
                .get("/sid/snr/{snr}", "0001ha3")
                .then()
                .statusCode(200)
                .body(containsString("11854898347"))
                .body(containsString("0001ha3"));
    }
}
