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
        log.info(Strings.padEnd(String.format("*** Pseudonymize field: %s ", req.getName()), 80, '*'));
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
        log.info(Strings.padEnd(String.format("*** Depseudonymize field: %s ", req.getName()), 80, '*'));
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
        log.info(Strings.padEnd(String.format("*** Repseudonymize field: %s ", req.getName()), 80, '*'));
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


    @Operation(summary = "Pseudonymize file", description = """
            Pseudonymize a file (JSON or CSV - or a zip with potentially multiple such files) by uploading the file.
                        
            The pseudonymized result will be streamed back.
                        
            Notice that you can specify the `targetContentType` if you want to convert to either of the supported file
            formats. E.g. your source could be a CSV file and the result could be a JSON file.

            Reduce transmission times by applying compression both to the source and target files.
            Specify `compression` if you want the result to be a zipped (and optionally) encrypted archive.
                        
            Pseudonymization will be applied according to a list of "rules" that target the fields of the file being
            processed. Each rule defines a `pattern` (according to [glob pattern matching](https://docs.oracle.com/javase/tutorial/essential/io/fileOps.html#glob))
            that identifies one or multiple fields, and a `func` that will be applied to the matching fields. Rules are
            processed in the order they are defined, and only the first matching rule will be applied (thus: rule ordering
            is important).
                        
            Pseudo rules will most times refer to crypto keys. You can provide your own keys to use (via the `keysets` param)
            or use one of the predefined keys: `ssb-common-key-1` or `ssb-common-key-2`.
            """
    )
    @Post("/pseudonymize/file")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(value = MediaType.APPLICATION_JSON)
    @SingleResult
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<Flowable<byte[]>> pseudonymizeFile(
            @Schema(implementation = PseudoRequest.class) String request, StreamingFileUpload data
    ) {
        log.info(Strings.padEnd(String.format("*** Pseudonymize file: %s", data.getFilename()), 80, '*'));
        log.debug("PseudoRequest {}", request);
        try {
            PseudoRequest req = Json.toObject(PseudoRequest.class, request);
            List<PseudoConfig> pseudoConfigs = pseudoConfigSplitter.splitIfNecessary(req.getPseudoConfig());

            final String correlationId = MDC.get("CorrelationID");
            RecordMapProcessor<PseudoMetadataProcessor> recordProcessor = recordProcessorFactory.newPseudonymizeRecordProcessor(pseudoConfigs, correlationId);
            ProcessFileResult res = processFile(data, PseudoOperation.PSEUDONYMIZE, recordProcessor, req.getTargetContentType(), req.getCompression());
            return HttpResponse.ok(res.getResponse()
                            .map(o -> o.getBytes(StandardCharsets.UTF_8)))
                    .characterEncoding(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.error(String.format("Failed to pseudonymize:%nrequest:%n%s", request), e);
            throw e;
        }
    }

    @Operation(
            summary = "Depseudonymize file",
            description = """
                    Depseudonymize a file (JSON or CSV - or a zip with potentially multiple such files) by uploading the file.
                                
                    Notice that only certain whitelisted users can depseudonymize data.
                                
                    The pseudonymized result will be streamed back.

                    Notice that you can specify the `targetContentType` if you want to convert to either of the supported file
                    formats. E.g. your source could be a CSV file and the result could be a JSON file.

                    Reduce transmission times by applying compression both to the source and target files.
                    Specify `compression` if you want the result to be a zipped (and optionally) encrypted archive.
                                
                    Depseudonymization will be applied according to a list of "rules" that target the fields of the file being
                    processed. Each rule defines a `pattern` (according to [glob pattern matching](https://docs.oracle.com/javase/tutorial/essential/io/fileOps.html#glob))
                    that identifies one or multiple fields, and a `func` that will be applied to the matching fields. Rules are
                    processed in the order they are defined, and only the first matching rule will be applied (thus: rule ordering
                    is important).
                                
                    Pseudo rules will most times refer to crypto keys. You can provide your own keys to use (via the `keysets` param)
                    or use one of the predefined keys: `ssb-common-key-1` or `ssb-common-key-2`.
                    """
    )
    @Post("/depseudonymize/file")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Secured({PseudoServiceRole.ADMIN})
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<Flowable<byte[]>> depseudonymizeFile(
            @Schema(implementation = PseudoRequest.class) String request, StreamingFileUpload data, Principal principal
    ) {
        log.info(Strings.padEnd(String.format("*** Depseudonymize file: %s", data.getFilename()), 80, '*'));
        log.debug("User: {}\n{}", principal.getName(), request);

        try {
            PseudoRequest req = Json.toObject(PseudoRequest.class, request);
            List<PseudoConfig> pseudoConfigs = pseudoConfigSplitter.splitIfNecessary(req.getPseudoConfig());

            final String correlationId = MDC.get("CorrelationID");
            RecordMapProcessor<PseudoMetadataProcessor> recordProcessor = recordProcessorFactory.newDepseudonymizeRecordProcessor(pseudoConfigs, correlationId);
            ProcessFileResult res = processFile(data, PseudoOperation.DEPSEUDONYMIZE, recordProcessor, req.getTargetContentType(), req.getCompression());
            return HttpResponse.ok(res.getResponse().map(o -> o.getBytes(StandardCharsets.UTF_8)))
                    .characterEncoding(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error(String.format("Failed to depseudonymize:%nrequest:%n%s", request), e);
            return HttpResponse.serverError(Flowable.error(e));
        }

    }

    @Operation(
            summary = "Repseudonymize file",
            description = """
                    Repseudonymize a file (JSON or CSV - or a zip with potentially multiple such files) by uploading the file.
                    Repseudonymization is done by first applying depseudonuymization and then pseudonymization to fields of the file.
                                
                    The pseudonymized result will be streamed back.
                                
                    Notice that you can specify the `targetContentType` if you want to convert to either of the supported file
                    formats. E.g. your source could be a CSV file and the result could be a JSON file.

                    Reduce transmission times by applying compression both to the source and target files.
                    Specify `compression` if you want the result to be a zipped (and optionally) encrypted archive.
                                
                    Repseudonymization will be applied according to a list of "rules" that target the fields of the file being
                    processed. Each rule defines a `pattern` (according to [glob pattern matching](https://docs.oracle.com/javase/tutorial/essential/io/fileOps.html#glob))
                    that identifies one or multiple fields, and a `func` that will be applied to the matching fields. Rules are
                    processed in the order they are defined, and only the first matching rule will be applied (thus: rule ordering
                    is important). Two sets of rules are provided: one that defines how to depseudonymize and a second that defines
                    how to pseudonymize. These sets of rules are linked to separate keysets.
                                
                    Pseudo rules will most times refer to crypto keys. You can provide your own keys to use (via the `keysets` param)
                    or use one of the predefined keys: `ssb-common-key-1` or `ssb-common-key-2`.
                    """
    )
    @Post("/repseudonymize/file")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Secured({PseudoServiceRole.ADMIN})
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<Flowable<byte[]>> repseudonymizeFile(
            @Schema(implementation = RepseudoRequest.class) String request, StreamingFileUpload data, Principal principal) {
        log.info(Strings.padEnd(String.format("*** Repseudonymize file: %s", data.getFilename()), 80, '*'));
        log.debug("User: {}\n{}", principal.getName(), request);

        try {
            RepseudoRequest req = Json.toObject(RepseudoRequest.class, request);
            final String correlationId = MDC.get("CorrelationID");
            RecordMapProcessor<PseudoMetadataProcessor> recordProcessor = recordProcessorFactory.newRepseudonymizeRecordProcessor(req.getSourcePseudoConfig(), req.getTargetPseudoConfig(), correlationId);
            ProcessFileResult res = processFile(data, PseudoOperation.REPSEUDONYMIZE, recordProcessor, req.getTargetContentType(), req.getCompression());
            return HttpResponse.ok(res.getResponse()
                            .map(o -> o.getBytes(StandardCharsets.UTF_8)))
                    .characterEncoding(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error(String.format("Failed to repseudonymize:%nrequest:%n%s", request), e);
            return HttpResponse.serverError(Flowable.error(e));
        }
    }

    private ProcessFileResult processFile(StreamingFileUpload data, PseudoOperation operation, RecordMapProcessor<PseudoMetadataProcessor> recordMapProcessor, MediaType targetContentType, TargetCompression targetCompression) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        targetContentType = MoreMediaTypes.validContentType(targetContentType);
        File tempFile = null;
        PseudoFileSource fileSource = null;

        try {
            // TODO: Rewrite to non-blocking
            tempFile = receiveFile(data).blockingGet();
            fileSource = new PseudoFileSource(tempFile);
            log.info("Received file ({}, {})", fileSource.getProvidedMediaType(), HumanReadableBytes.fromBin(tempFile.length()));
            log.info("{} {} files with content type {}", operation, fileSource.getFiles().size(), fileSource.getMediaType());
            log.info("Target content type: {}", targetContentType);

            final StreamProcessor streamProcessor = streamProcessorFactory.newStreamProcessor(fileSource.getMediaType(), recordMapProcessor);
            final PseudoMetadataProcessor metadataProcessor = recordMapProcessor.getMetadataProcessor();
            // Metadata will be processes in parallel with the data, but must be collected separately
            final Flowable<String> metadata = Flowable.fromPublisher(metadataProcessor.getMetadata());
            final Flowable<String> logs = Flowable.fromPublisher(metadataProcessor.getLogs());
            final Flowable<String> metrics = Flowable.fromPublisher(metadataProcessor.getMetrics());
            // Preprocess the file contents - if necessary
            Flowable<String> res = preprocessStream(fileSource.getInputStream(), streamProcessor)
                    .doOnError(throwable -> log.error("Preprocessing failed", throwable))
                    .doOnComplete(() -> log.info("Preprocessing took {}", stopwatch.elapsed()))
                    // And then do the actual proccessing/transformations
                    .andThen(processStream(fileSource.getInputStream(), streamProcessor, targetContentType)
                            .doOnSubscribe((subscription) -> log.info("Start processing..."))
                            .doOnError(throwable -> {
                                log.error("Response failed", throwable);
                                metadataProcessor.onErrorAll(throwable);
                            })
                            .doOnComplete(() -> {
                                log.info("{} took {}", operation, stopwatch.stop().elapsed());
                                // Signal the metadataProcessor to stop collecting metadata
                                metadataProcessor.onCompleteAll();
                            })
                    );
            return new ProcessFileResult(targetContentType, PseudoResponseSerializer.serialize(res, metadata, logs, metrics));
        } finally {
            try {
                if (fileSource != null) {
                    fileSource.cleanup();
                }
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile.toPath());
                }
            } catch (IOException e) {
                log.warn("Error cleaning up", e);
            }
        }
    }

    private Completable preprocessStream(InputStream is, StreamProcessor streamProcessor) {
        return streamProcessor.init(is);
    }
    private Flowable<String> processStream(InputStream is, StreamProcessor streamProcessor, MediaType targetContentType) {
        return streamProcessor.process(is, RecordMapSerializerFactory.newFromMediaType(targetContentType));
    }

    private Single<File> receiveFile(StreamingFileUpload data) {

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("temp");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        File tempFile = tempDir.resolve(data.getFilename()).toFile();
        log.debug("Receive file - stored temporarily at " + tempFile.getAbsolutePath());
        return Single.fromPublisher(data.transferTo(tempFile))
                .map(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        return tempFile;
                    } else {
                        throw new RuntimeException("Error receiving file " + tempFile);
                    }
                });
    }

    @Data
    public static class PseudoRequest {

        /**
         * The pseudonymization config to apply
         */
        private PseudoConfig pseudoConfig;

        /**
         * The content type of the resulting file.
         */
        @Schema(implementation = String.class, allowableValues = {
                MediaType.APPLICATION_JSON, MoreMediaTypes.TEXT_CSV})
        private MediaType targetContentType;

        /**
         * Specify this if you want to compress and password protect the payload. The archive will be encrypted with AES256.
         */
        private TargetCompression compression;
    }

    @Data
    public static class RepseudoRequest {

        /**
         * The source pseudonymization config
         */
        private PseudoConfig sourcePseudoConfig;

        /**
         * The target pseudonymization config
         */
        private PseudoConfig targetPseudoConfig;

        /**
         * The content type of the resulting file.
         */
        @Schema(implementation = String.class, allowableValues = {
                MediaType.APPLICATION_JSON, MoreMediaTypes.TEXT_CSV})
        private MediaType targetContentType;

        /**
         * Specify this if you want to compress and password protect the payload. The archive will be encrypted with AES256.
         */
        private TargetCompression compression;
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

    @Data
    public static class ProcessFileResult {
        @Schema(implementation = String.class)
        private final MediaType targetContentType;

        private final Flowable<String> response;
    }

    @Data
    public static class TargetCompression {

        /**
         * The password on the resulting archive
         */
        @NotBlank
        @Schema(implementation = String.class)
        @Min(9)
        private char[] password;
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
