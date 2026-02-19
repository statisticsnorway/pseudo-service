package no.ssb.dlp.pseudo.service.pseudo;

import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import io.micronaut.core.async.annotation.SingleResult;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.*;
import io.micronaut.http.hateoas.JsonError;
import io.micronaut.http.hateoas.Link;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.ssb.dapla.dlp.pseudo.func.PseudoFuncFactory;
import no.ssb.dlp.pseudo.core.PseudoOperation;
import no.ssb.dlp.pseudo.core.StreamProcessor;
import no.ssb.dlp.pseudo.core.exception.NoSuchPseudoKeyException;
import no.ssb.dlp.pseudo.core.file.MoreMediaTypes;
import no.ssb.dlp.pseudo.core.file.PseudoFileSource;
import no.ssb.dlp.pseudo.core.map.RecordMapProcessor;
import no.ssb.dlp.pseudo.core.map.RecordMapSerializerFactory;
import no.ssb.dlp.pseudo.core.tink.model.EncryptedKeysetWrapper;
import no.ssb.dlp.pseudo.core.util.HumanReadableBytes;
import no.ssb.dlp.pseudo.core.util.Json;
import no.ssb.dlp.pseudo.service.pseudo.metadata.PseudoMetadataProcessor;
import no.ssb.dlp.pseudo.service.security.PseudoServiceRole;
import no.ssb.dlp.pseudo.service.sid.InvalidSidSnapshotDateException;
import no.ssb.dlp.pseudo.service.sid.SidIndexUnavailableException;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.MDC;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;

/*
TODO: Once file based endpoints are removed implement data classes for the response type of remaining endpoints
  so that we can provide accurate swagger documentation.
  See https://github.com/statisticsnorway/pseudo-service/issues/95
 */

@RequiredArgsConstructor
@Controller
@Slf4j
@Secured({PseudoServiceRole.USER, PseudoServiceRole.ADMIN})
@Tag(name = "Pseudo operations")
public class PseudoController {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private final StreamProcessorFactory streamProcessorFactory;
    private final RecordMapProcessorFactory recordProcessorFactory;
    private final PseudoConfigSplitter pseudoConfigSplitter;

    /**
     * Pseudonymizes a field.
     *
     * @param request JSON string representing a {@link PseudoFieldRequest} object.
     * @return HTTP response containing a {@link HttpResponse<Flowable>} object.
     */

    @Operation(summary = "Pseudonymize field", description = "Pseudonymize a field.")
    @Produces(MediaType.APPLICATION_JSON)
    @Post(value = "/pseudonymize/field", consumes = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<Flowable<byte[]>> pseudonymizeField(
            @Schema(implementation = PseudoFieldRequest.class) String request
    ) {
        PseudoFieldRequest req = Json.toObject(PseudoFieldRequest.class, request);
        if (log.isDebugEnabled()) {
            log.debug(Strings.padEnd(String.format("*** Pseudonymize field: %s ", req.getName()), 80, '*'));
        }
        PseudoField pseudoField = new PseudoField(req.getName(), req.getPattern(), req.getPseudoFunc(), req.getKeyset());
        try {
            final String correlationId = MDC.get("CorrelationID");

            return HttpResponse.ok(
                    pseudoField.process(
                            pseudoConfigSplitter,
                            recordProcessorFactory,
                            req.values,
                            PseudoOperation.PSEUDONYMIZE,
                            correlationId
                            )
                            .map(o -> o.getBytes(StandardCharsets.UTF_8))
                    )
                    .characterEncoding(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return HttpResponse.serverError(Flowable.error(e));
        }
    }

    @Operation(summary = "Pseudonymize field fast", description = "Pseudonymize a single field with optimized path.")
    @Produces(MediaType.APPLICATION_JSON)
    @Post(value = "/pseudonymize/field-fast", consumes = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<Flowable<byte[]>> pseudonymizeFieldFast(
            @Schema(implementation = PseudoFieldRequest.class) String request,
            @QueryValue(defaultValue = "false") boolean minimalMetricsMode
    ) {
        PseudoFieldRequest req = Json.toObject(PseudoFieldRequest.class, request);
        if (log.isDebugEnabled()) {
            log.debug(Strings.padEnd(String.format("*** Pseudonymize field fast: %s ", req.getName()), 80, '*'));
        }
        PseudoField pseudoField = new PseudoField(req.getName(), req.getPattern(), req.getPseudoFunc(), req.getKeyset());
        try {
            final String correlationId = MDC.get("CorrelationID");

            return HttpResponse.ok(
                            pseudoField.processFastPseudonymize(
                                    pseudoConfigSplitter,
                                    recordProcessorFactory,
                                    req.values,
                                    correlationId,
                                    minimalMetricsMode
                            )
                                    .map(o -> o.getBytes(StandardCharsets.UTF_8))
                    )
                    .characterEncoding(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return HttpResponse.serverError(Flowable.error(e));
        }
    }

    /**
     * Depseudonymizes a field.
     *
     * @param request JSON string representing a {@link DepseudoFieldRequest} object.
     * @return HTTP response containing a {@link HttpResponse<Flowable>} object.
     */
    @Operation(summary = "Depseudonymize field", description = "Depseudonymize a field.")
    @Produces(MediaType.APPLICATION_JSON)
    @Secured({PseudoServiceRole.ADMIN})
    @Post(value = "/depseudonymize/field", consumes = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<Flowable<byte[]>> depseudonymizeField(
            @Schema(implementation = DepseudoFieldRequest.class) String request
    ) {
        DepseudoFieldRequest req = Json.toObject(DepseudoFieldRequest.class, request);
        if (log.isDebugEnabled()) {
            log.debug(Strings.padEnd(String.format("*** Depseudonymize field: %s ", req.getName()), 80, '*'));
        }
        PseudoField pseudoField = new PseudoField(req.getName(), req.getPattern(), req.getPseudoFunc(), req.getKeyset());
        try {

            final String correlationId = MDC.get("CorrelationID");

            return HttpResponse.ok(pseudoField.process(
                    pseudoConfigSplitter, recordProcessorFactory,req.values, PseudoOperation.DEPSEUDONYMIZE, correlationId)
                            .map(o -> o.getBytes(StandardCharsets.UTF_8)))
                    .characterEncoding(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return HttpResponse.serverError(Flowable.error(e));
        }
    }

    /**
     * Repseudonymizes a field.
     *
     * @param request JSON string representing a {@link RepseudoFieldRequest} object.
     * @return HTTP response containing a {@link HttpResponse<Flowable>} object.
     */
    @Operation(summary = "Repseudonymize field", description = "Repseudonymize a field.")
    @Produces(MediaType.APPLICATION_JSON)
    @Secured({PseudoServiceRole.ADMIN})
    @Post(value = "/repseudonymize/field", consumes = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<Flowable<byte[]>> repseudonymizeField(
            @Schema(implementation = RepseudoFieldRequest.class) String request
    ) {
        RepseudoFieldRequest req = Json.toObject(RepseudoFieldRequest.class, request);
        if (log.isDebugEnabled()) {
            log.debug(Strings.padEnd(String.format("*** Repseudonymize field: %s ", req.getName()), 80, '*'));
        }
        PseudoField sourcePseudoField = new PseudoField(req.getName(), req.getPattern(), req.getSourcePseudoFunc(), req.getSourceKeyset());
        PseudoField targetPseudoField = new PseudoField(req.getName(), req.getPattern(), req.getTargetPseudoFunc(), req.getTargetKeyset());
        try {

            final String correlationId = MDC.get("CorrelationID");
            return HttpResponse.ok(
                    sourcePseudoField.process(recordProcessorFactory, req.values, targetPseudoField, correlationId)
                            .map(o -> o.getBytes(StandardCharsets.UTF_8)))
                    .characterEncoding(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return HttpResponse.serverError(Flowable.error(e));
        }
    }

    @Data
    public static class PseudoFieldRequest {

        /**
         * The pseudonymization config to apply
         */
        private String pseudoFunc;
        private EncryptedKeysetWrapper keyset;
        private String name;
        private String pattern;
        private List<String> values;
    }

    @Data
    public static class DepseudoFieldRequest {

        /**
         * The depseudonymization config to apply
         */
        private String pseudoFunc;
        private EncryptedKeysetWrapper keyset;
        private String name;
        private String pattern;
        private List<String> values;
    }

    @Data
    public static class RepseudoFieldRequest {

        /**
         * The repseudonymization config to apply
         */
        private String sourcePseudoFunc;
        private String targetPseudoFunc;
        private EncryptedKeysetWrapper sourceKeyset;
        private EncryptedKeysetWrapper targetKeyset;
        private String name;
        private String pattern;
        private List<String> values;
    }


    @Error
    public HttpResponse<JsonError> unknownPseudoKeyError(HttpRequest request, NoSuchPseudoKeyException e) {
        JsonError error = new JsonError(e.getMessage())
                .link(Link.SELF, Link.of(request.getUri()));
        return HttpResponse.<JsonError>badRequest().body(error);
    }

    @Error
    public HttpResponse<JsonError> sidIndexUnavailable(HttpRequest request, SidIndexUnavailableException e) {
        JsonError error = new JsonError(e.getMessage())
                .link(Link.SELF, Link.of(request.getUri()));
        return HttpResponse.<JsonError>serverError().status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    @Error
    public HttpResponse<JsonError> illegalArgument(HttpRequest request, IllegalArgumentException e) {
        JsonError error = new JsonError(e.getMessage())
                .link(Link.SELF, Link.of(request.getUri()));
        return HttpResponse.<JsonError>badRequest().body(error);
    }

    @Error
    public HttpResponse<JsonError> sidVersionInvalid(HttpRequest request, PseudoFuncFactory.PseudoFuncInitException e) {
        if (e.getCause() instanceof InvocationTargetException && e.getCause().getCause() instanceof InvalidSidSnapshotDateException){
            JsonError error = new JsonError(e.getCause().getCause().getMessage())
                    .link(Link.SELF, Link.of(request.getUri()));
            return HttpResponse.<JsonError>badRequest().body(error);
        }
        JsonError error = new JsonError(e.getMessage())
                .link(Link.SELF, Link.of(request.getUri()));
        return HttpResponse.<JsonError>serverError().status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }
}
