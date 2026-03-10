package no.ssb.dlp.pseudo.service.accessgroups;

import io.micronaut.cache.annotation.Cacheable;
import io.opentelemetry.api.trace.Span;
import io.reactivex.Flowable;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import no.ssb.dlp.pseudo.service.tracing.WithSpan;

import java.util.ArrayList;
import java.util.List;

@Singleton
@RequiredArgsConstructor
public class CloudIdentityService {
    private final CloudIdentityClient cloudIdentityClient;

    @WithSpan
    @Cacheable(value = "cloud-identity-service-cache", parameters = {"groupEmail"})
    public List<Membership> listMembers(String groupEmail) {
        final var currentSpan = Span.current();

        return Flowable.fromPublisher(cloudIdentityClient.lookup(groupEmail))
                .doOnNext(lookupResponse -> currentSpan.setAttribute("lookupResponse.groupEmail", lookupResponse.getGroupName()))
                .flatMap(lookupResponse -> fetchMemberships(lookupResponse.getGroupName(), null,
                        new ArrayList<>()))
                .blockingFirst();
    }

    /**
     * Paginate through all memberships of a group.
     *
     * @param groupId        the id of the group
     * @param nextPageToken  a token for pagination (will be null on first call)
     * @param allMemberships a list that will be populated with all memberships
     * @return the list of all memberships
     */
    @WithSpan
    protected Flowable<List<Membership>> fetchMemberships(String groupId, String nextPageToken,
                                                        List<Membership> allMemberships) {
        if (groupId == null || groupId.isEmpty()) {
            return Flowable.just(allMemberships);
        }
        return Flowable.fromPublisher(cloudIdentityClient.listMembers(groupId, nextPageToken))
                .flatMap(membershipResponse -> {
                    allMemberships.addAll(membershipResponse.getMemberships());
                    String nextToken = membershipResponse.getNextPageToken();
                    return nextToken != null ?
                            fetchMemberships(groupId, nextToken, allMemberships) :
                            Flowable.just(allMemberships);
                });
    }
}
