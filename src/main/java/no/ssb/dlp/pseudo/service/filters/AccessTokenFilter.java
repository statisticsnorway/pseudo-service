package no.ssb.dlp.pseudo.service.filters;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.micronaut.cache.annotation.Cacheable;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Nullable;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import no.ssb.dlp.pseudo.service.tracing.WithSpan;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.FileInputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * This filter will obtain an {@link AccessToken} and add it to the request. It can use credentials from either
 * Google's default Application Default Credentials or a custom Service Account (as opposed to the
 * {@see io.micronaut.gcp.http.client.GoogleAuthFilter} which only uses the Compute metadata server).
 */
@AccessTokenFilterMatcher
@Singleton
@Data
@Slf4j
public class AccessTokenFilter implements HttpClientFilter {

    @Inject
    private ApplicationContext applicationContext;
    @Nullable
    @Value("${gcp.http.client.filter.project-id}")
    private String projectId;
    private final GoogleCredentials credentials;

    @SneakyThrows
    public AccessTokenFilter(@Nullable @Value("${gcp.http.client.filter.credentials-path}") String credentialsPath) {
        if (credentialsPath == null) {
            log.info("Using Application Default Credentials");
            this.credentials = GoogleCredentials.getApplicationDefault();
        } else {
            log.info("Using Credentials from Service Account file: {}", credentialsPath);
            this.credentials = GoogleCredentials.fromStream(
                    new FileInputStream(credentialsPath));
        }
    }

    @SneakyThrows
    @WithSpan
    public Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
        final var currentSpan = Span.current();
        currentSpan.setAttribute("request.url", request.getUri().toString());
        final var config = request.getAttribute("micronaut.http.serviceId").map(Object::toString).flatMap(this::getConfig);
        currentSpan.addEvent("Add bearer auth", Instant.now());
        if (config.isPresent()) {
            request.bearerAuth(getAccessToken(config.get().getAudience()));
        } else {
            request.bearerAuth(getAccessToken(getAudienceFromRequest(request)));
        }
        currentSpan.addEvent("Add bearer auth (finished)", Instant.now());
        currentSpan.addEvent("Set project ID", Instant.now());
        setProjectIdHeader(request);
        currentSpan.addEvent("Set project ID (finished)", Instant.now());
        return chain.proceed(request);
    }

    private void setProjectIdHeader(MutableHttpRequest<?> request) {
        if (projectId != null) {
            log.debug("Using projectId {} from config to override qoutaProjectId", projectId);
            request.getHeaders().add("x-goog-user-project", projectId);
        }
    }

    @SneakyThrows
    @WithSpan
    protected String getAccessToken(String audience) {
        return credentials.createScoped(audience).refreshAccessToken().getTokenValue();
    }

    @Cacheable(value="access-token-filter-cache", parameters = {"serviceId"})
    @WithSpan
    protected Optional<AccessTokenFilterConfig> getConfig(String serviceId) {
        return Optional
                .ofNullable(applicationContext)
                .flatMap(ac ->
                    ac.findBean(AccessTokenFilterConfig.class, Qualifiers.byName(serviceId))
                );
    }

    private String getAudienceFromRequest(final MutableHttpRequest<?> request) {
        URI fullURI = request.getUri();
        return fullURI.getScheme() + "://" + fullURI.getHost();
    }

}
