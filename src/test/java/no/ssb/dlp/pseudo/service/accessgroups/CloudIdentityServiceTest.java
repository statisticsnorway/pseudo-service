package no.ssb.dlp.pseudo.service.accessgroups;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@MicronautTest
public class CloudIdentityServiceTest {

    @Inject
    CloudIdentityService cloudIdentityService;
    @Inject
    CloudIdentityClient cloudIdentityClient;
    @MockBean(CloudIdentityClient.class)
    CloudIdentityClient cloudIdentityClient() {
        return mock(CloudIdentityClient.class);
    }

    @Test
    public void testInvokeLookup() {
        // first look up the group name from the email address
        when(cloudIdentityClient.lookup(eq("access-group@ssb.no"))).thenReturn(Publishers.just(
                LookupResponse.builder().name("groups/xxyyzzz").build())
        );
        // "groups/" prefix should be stripped off when calling listMembers
        when(cloudIdentityClient.listMembers(eq("xxyyzzz"), isNull())).thenReturn(Publishers.just(
                MembershipResponse.builder().memberships(List.of(
                        Membership.builder().name("groups/xxyyzzz/memberships/1").build(),
                        Membership.builder().name("groups/xxyyzzz/memberships/2").build())
                ).build())
        );
        List<Membership> members = cloudIdentityService.listMembers("access-group@ssb.no");
        assertThat(members).hasSize(2);

        verify(cloudIdentityClient, times(1)).lookup(anyString());
        verify(cloudIdentityClient, times(1)).listMembers(anyString(), any());

        // Reset mock and simulate another request that shoould hit the cache
        reset(cloudIdentityClient);
        members = cloudIdentityService.listMembers("access-group@ssb.no");
        assertThat(members).hasSize(2);
        verifyNoInteractions(cloudIdentityClient);
    }

    @Test
    public void testInvokeWithPagination() {
        // first look up the group name from the email address
        when(cloudIdentityClient.lookup(eq("paginated-access-group@ssb.no"))).thenReturn(Publishers.just(
                LookupResponse.builder().name("groups/zzzccc").build())
        );
        // Simulate that listMembers returns a paginated result
        when(cloudIdentityClient.listMembers(eq("zzzccc"), isNull())).thenReturn(Publishers.just(
                MembershipResponse.builder().memberships(List.of(
                        Membership.builder().name("groups/zzzccc/memberships/1").build(),
                        Membership.builder().name("groups/zzzccc/memberships/2").build())
                ).nextPageToken("nextPageToken").build())
        );
        when(cloudIdentityClient.listMembers(eq("zzzccc"), eq("nextPageToken"))).thenReturn(Publishers.just(
                MembershipResponse.builder().memberships(List.of(
                        Membership.builder().name("groups/zzzccc/memberships/3").build()
                )).build())
        );
        List<Membership> members = cloudIdentityService.listMembers("paginated-access-group@ssb.no");
        assertThat(members).hasSize(3);

        verify(cloudIdentityClient, times(1)).lookup(anyString());
        verify(cloudIdentityClient, times(2)).listMembers(anyString(), any());
    }
}
