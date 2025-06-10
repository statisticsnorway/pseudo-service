package no.ssb.dlp.pseudo.service.pseudo;

import io.reactivex.Flowable;

public class PseudoResponseSerializer {
    private static final long LOG_LIMIT = 100;
    /**
     * Combine the <code>Flowable</code> JSON-objects (data, metadata, etc) into a single <code>Flowable</code> that
     * represents the JSON-structure.
     *
     * @param data      a flowable of String elements, each in JSON-format
     * @param metadata  a flowable of String elements, each in JSON-format
     * @param logs      a flowable of String elements, each in JSON-format
     * @param metrics   a flowable of String elements, each in JSON-format
     * @param metrics
     */
    public static Flowable<String> serialize(Flowable<String> data, Flowable<String> metadata,
                                             Flowable<String> logs, Flowable<String> metrics) {
        return enclose(data.concatMap(item -> Flowable.just(item, ","))
                .startWith("\"data\": [")
                .skipLast(1) // Skip last comma
                .concatWith(Flowable.just("], \"datadoc_metadata\": {\"pseudo_variables\": ["))
                .concatWith(metadata.concatMap(item -> Flowable.just(item, ",")).skipLast(1))
                .concatWith(Flowable.just("]}, \"metrics\": ["))
                .concatWith(metrics.concatMap(item -> Flowable.just(item, ",")).skipLast(1))
                .concatWith(Flowable.just("], \"logs\": ["))
                .concatWith(logs.take(LOG_LIMIT).concatMap(item -> Flowable.just(item, ",")).skipLast(1))
                .concatWith(Flowable.just("]")));
    }

    private static Flowable<String> enclose(Flowable<String> contents) {
        return Flowable.concat(Flowable.just("{"), contents, Flowable.just("}"));
    }

}
