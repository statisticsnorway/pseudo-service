package no.ssb.dlp.pseudo.service.pseudo;

import com.google.common.collect.Lists;
import io.reactivex.Flowable;
import no.ssb.dlp.pseudo.core.util.Json;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.util.Map;

class PseudoResponseSerializerTest {

    @Test
    void testSerializeMap() throws JSONException {
        Flowable<String> data = Flowable.just(
                Map.of("person", Map.of("name", Map.of("firstName", "Donald", "lastName", "Duck"))),
                Map.of("person", Map.of("name", Map.of("firstName", "Bolla", "lastName", "Bollerud")))
        ).map(Json::from);
        Flowable<String> metadata = Flowable.just(
                Json.from(Map.of("variable_descriptions", Map.of("name", Map.of("type", "string", "description", "name variable"))))
        );
        Flowable<String> logs = Flowable.just(
                "Log line 1", "Log line 2"
        ).map(Json::from);
        Flowable<String> metrics = Flowable.just(
                Map.of("metric1", 1))
                .map(Json::from);
        String got = String.join("", Lists.newArrayList(PseudoResponseSerializer.serialize(data, metadata, logs, metrics).blockingIterable()));
        String want = """
                {
                     "data": [
                       {
                         "person": {
                           "name": {
                             "lastName": "Duck",
                             "firstName": "Donald"
                           }
                         }
                       },
                       {
                         "person": {
                           "name": {
                             "lastName": "Bollerud",
                             "firstName": "Bolla"
                           }
                         }
                       }
                     ],
                     "datadoc_metadata": {
                       "pseudo_variables": [
                         {
                           "variable_descriptions": {
                             "name": {
                               "description": "name variable",
                               "type": "string"
                             }
                           }
                         }
                       ]
                     },
                     "metrics": [
                       {
                         "metric1": 1
                       }
                     ],
                     "logs": [
                       "Log line 1",
                       "Log line 2"
                     ]
                   }
                """;
        JSONAssert.assertEquals(want, got, JSONCompareMode.STRICT);
    }}