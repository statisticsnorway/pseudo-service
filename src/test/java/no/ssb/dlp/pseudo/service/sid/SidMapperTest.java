package no.ssb.dlp.pseudo.service.sid;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import nl.altindag.log.LogCaptor;
import no.ssb.dapla.dlp.pseudo.func.PseudoFuncInput;
import no.ssb.dapla.dlp.pseudo.func.PseudoFuncOutput;
import no.ssb.dapla.dlp.pseudo.func.map.MapFailureStrategy;
import no.ssb.dapla.dlp.pseudo.func.map.MapFuncConfig;
import no.ssb.dapla.dlp.pseudo.func.map.Mapper;
import no.ssb.dlp.pseudo.service.Application;
import org.apache.groovy.util.Maps;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static no.ssb.dlp.pseudo.service.sid.SidMapper.*;
import static org.mockito.Mockito.*;

/**
 * Test {@link SidMapper} indirectly via the JDK's ServiceLoader. This requires static mocking of the
 * {@link Application} class.
 */
@MicronautTest
public class SidMapperTest {

    @Inject
    SidService sidService;
    @Inject
    ApplicationContext context;

    LogCaptor logCaptor = LogCaptor.forClass(SidMapper.class);

    @Test
    public void testInvokeMapperFunc() {
        when(sidService.lookupFnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("11854898347", new SidInfo.SidInfoBuilder().snr("0001ha3").build()))
        );
        // Use static mocking to override the application context
        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(new HashMap<>());
            mapper.init(PseudoFuncInput.of("11854898347"));
            String mappedSid = mapper.map(PseudoFuncInput.of("11854898347")).getValue();

            verify(sidService, times(1)).lookupFnr(anyList(), eq(Optional.empty()));
            Assertions.assertEquals("0001ha3", mappedSid);
            assertLogsForIdentifiers("11854898347", "0001ha3");
        }
    }

    @Test
    public void testInvokeRestoreFunc(){
        when(sidService.lookupSnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("0001ha3", new SidInfo.SidInfoBuilder().fnr("11854898347").build()))
        );
        // Use static mocking to override the application context
        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(new HashMap<>());
            mapper.init(PseudoFuncInput.of("0001ha3"));
            String mappedSid = mapper.restore(PseudoFuncInput.of("0001ha3")).getValue();

            verify(sidService, times(1)).lookupSnr(anyList(), eq(Optional.empty()));
            Assertions.assertEquals("11854898347", mappedSid);
            assertLogsForIdentifiers( "11854898347", "0001ha3");
        }
    }

    @Test
    public void testMapValidVersion() {
        when(sidService.lookupFnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("11854898347", new SidInfo.SidInfoBuilder().snr("0001ha3").build()))
        );
        when(sidService.getSnapshots()).thenReturn(Publishers.just(
                SnapshotInfo.builder().items(List.of("2023-04-25")).build()
        ));
        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(Map.of("snapshotDate", "2023-04-25"));
            mapper.init(PseudoFuncInput.of("11854898347"));
            String mappedSid = mapper.map(PseudoFuncInput.of("11854898347")).getValue();

            verify(sidService, times(1)).getSnapshots();
            verify(sidService, times(1)).lookupFnr(anyList(), eq(Optional.of("2023-04-25")));
            Assertions.assertEquals("0001ha3", mappedSid);
        }
    }
    @Test
    public void testMapVersionEarlierThanAvailable() {
        when(sidService.getSnapshots()).thenReturn(Publishers.just(
                SnapshotInfo.builder().items(List.of("2023-04-25")).build()
        ));
        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            Exception exception = Assertions.assertThrows(RuntimeException.class, () -> {
                mapper.setConfig(Map.of("snapshotDate", "2005-02-27"));
            });
            Assertions.assertEquals("Requested date is of an earlier date than all available SID dates. Valid dates are: 2023-04-25", exception.getMessage());
        }
    }

    @Test
    public void testMapVersionInvalidFormat() {
        when(sidService.getSnapshots()).thenReturn(Publishers.just(
                SnapshotInfo.builder().items(List.of("2023-04-25")).build()
        ));
        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            Exception exception = Assertions.assertThrows(RuntimeException.class, () -> {
                mapper.setConfig(Map.of("snapshotDate", "25-04-2023"));
            });
            Assertions.assertEquals("Invalid snapshot date format. Valid dates are: 2023-04-25", exception.getMessage());
        }
    }

    @Test
    public void testMappingForFnrWithValidSnr(){
        when(sidService.lookupFnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("11854898347", new SidInfo.SidInfoBuilder().snr("0001ha3").build()))
        );

        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(new HashMap<>());
            mapper.init(PseudoFuncInput.of("11854898347"));
            String mappedSid = mapper.map(PseudoFuncInput.of("11854898347")).getValue();

            verify(sidService, times(1)).lookupFnr(anyList(), eq(Optional.empty()));
            Assertions.assertEquals("0001ha3", mappedSid);
            assertLogsForIdentifiers("11854898347", "0001ha3");
        }

    }
    @Test
    public void testIncorrectMappingForFnr(){
        when(sidService.lookupFnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("11854898346", new SidInfo.SidInfoBuilder().snr("11854898346").build()))
        );

        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(new HashMap<>());
            mapper.init(PseudoFuncInput.of("11854898346"));
            PseudoFuncOutput output = mapper.map(PseudoFuncInput.of("11854898346"));

            Assertions.assertEquals("Incorrect SID-mapping for fnr 118548*****. Mapping returned the original fnr!", output.getWarnings().getFirst());
            verify(sidService, times(1)).lookupFnr(anyList(), eq(Optional.empty()));
            assertLogsForIdentifiers("11854898346", "");
        }
    }
    @Test
    public void testMapForFnrWithNoSnr(){
        when(sidService.lookupFnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("11854898346", new SidInfo.SidInfoBuilder().snr(null).build()))
        );

        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(new HashMap<>());
            mapper.init(PseudoFuncInput.of("11854898346"));
            // Mapping should not be found - return original (default behaviour)
            PseudoFuncOutput output = mapper.map(PseudoFuncInput.of("11854898346"));
            Assertions.assertEquals("11854898346", output.getValue());
            Assertions.assertEquals(MapFailureStrategy.RETURN_ORIGINAL.name(),
                    output.getMetadata().get(MAP_FAILURE_METADATA));

            verify(sidService, times(1)).lookupFnr(anyList(), eq(Optional.empty()));
            assertLogsForIdentifiers("11854898346", "");
        }
    }

    @Test
    public void testMapForFnrWithNoSnrAndFailureStrategyReturnNull(){
        when(sidService.lookupFnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("11854898346", new SidInfo.SidInfoBuilder().snr(null).build()))
        );

        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(Maps.of(MapFuncConfig.Param.MAP_FAILURE_STRATEGY, MapFailureStrategy.RETURN_NULL));
            mapper.init(PseudoFuncInput.of("11854898346"));
            // Mapping should not be found - return null as instructed
            PseudoFuncOutput output = mapper.map(PseudoFuncInput.of("11854898346"));
            Assertions.assertNull(output.getValue());
            Assertions.assertEquals(MapFailureStrategy.RETURN_NULL.name(),
                    output.getMetadata().get(MAP_FAILURE_METADATA));

            verify(sidService, times(1)).lookupFnr(anyList(), eq(Optional.empty()));
            assertLogsForIdentifiers("11854898346", "");
        }
    }
    @Test
    public void testMappingForSnrWithValidFnr(){
        when(sidService.lookupSnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("0001ha3", new SidInfo.SidInfoBuilder().fnr("11854898347").build()))
        );

        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(new HashMap<>());
            mapper.init(PseudoFuncInput.of("0001ha3"));
            String mappedSid = mapper.restore(PseudoFuncInput.of("0001ha3")).getValue();
            verify(sidService, times(1)).lookupSnr(anyList(), eq(Optional.empty()));
            Assertions.assertEquals("11854898347", mappedSid);
            assertLogsForIdentifiers("11854898346", "");
        }

    }
    @Test
    public void testIncorrectMappingForSnr(){
        when(sidService.lookupSnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("0001ha3", new SidInfo.SidInfoBuilder().fnr("0001ha3").build()))
        );

        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(new HashMap<>());
            mapper.init(PseudoFuncInput.of("0001ha3"));
            PseudoFuncOutput output = mapper.restore(PseudoFuncInput.of("0001ha3"));

            Assertions.assertEquals("Incorrect SID-mapping for snr 000****. Mapping returned the original snr!", output.getWarnings().getFirst());
            verify(sidService, times(1)).lookupSnr(anyList(), eq(Optional.empty()));
            assertLogsForIdentifiers("11854898346", "0001ha3");
        }
    }
    @Test
    public void testMapForSnrWithNoFnr(){
        when(sidService.lookupSnr(anyList(), any(Optional.class))).thenReturn(Publishers.just(
                Maps.of("0001ha3", new SidInfo.SidInfoBuilder().fnr(null).build()))
        );

        try (final var application = mockStatic(Application.class)) {
            application.when(Application::getContext).thenReturn(context);
            Mapper mapper = ServiceLoader.load(Mapper.class).findFirst().orElseThrow(() ->
                    new RuntimeException("SidMapper class not found"));
            mapper.setConfig(new HashMap<>());
            mapper.init(PseudoFuncInput.of("0001ha3"));
            // Mapping should not be found - return original
            PseudoFuncOutput output = mapper.restore(PseudoFuncInput.of("0001ha3"));
            Assertions.assertEquals("0001ha3", output.getValue());
            Assertions.assertEquals(MapFailureStrategy.RETURN_ORIGINAL.name(),
                    output.getMetadata().get(MAP_FAILURE_METADATA));

            verify(sidService, times(1)).lookupSnr(anyList(), eq(Optional.empty()));
            assertLogsForIdentifiers("11854898346", "0001ha3");
        }
    }
    private void assertLogsForIdentifiers(String fnr, String snr) {
       for(String infoLog : logCaptor.getInfoLogs()){
           if(!fnr.isEmpty())
               Assertions.assertEquals(false, infoLog.contains(fnr),"Info logs must not log fnr in original format");
           if(!snr.isEmpty())
               Assertions.assertEquals(false, infoLog.contains(snr),"Info logs must not log snr in original format");
        }
       for(String warnLog : logCaptor.getWarnLogs()){
           if(!fnr.isEmpty())
               Assertions.assertEquals(false, warnLog.contains(fnr),"Warn logs must not log fnr in original format");
           if(!snr.isEmpty())
               Assertions.assertEquals(false, warnLog.contains(snr),"Warn logs must not log snr in original format");
       }
    }

    @MockBean(SidService.class)
    SidService sidService() {
        return mock(SidService.class);
    }
}
