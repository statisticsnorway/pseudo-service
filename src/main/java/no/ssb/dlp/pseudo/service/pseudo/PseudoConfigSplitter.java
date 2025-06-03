package no.ssb.dlp.pseudo.service.pseudo;

import no.ssb.crypto.tink.fpe.UnknownCharacterStrategy;
import no.ssb.dapla.dlp.pseudo.func.tink.fpe.TinkFpeFuncConfig;
import no.ssb.dlp.pseudo.core.func.PseudoFuncDeclaration;
import no.ssb.dlp.pseudo.core.func.PseudoFuncRule;

import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.List;

import static no.ssb.dlp.pseudo.core.func.PseudoFuncNames.FF31;
import static no.ssb.dlp.pseudo.core.func.PseudoFuncNames.MAP_SID;

/**
 * Split a PseudoConfig into multiple PseudoConfig objects to support chaining of SID Mapping functions.
 *
 * Disclaimer: This is a stop gap "solution", used to support chaining of SID Mapping with pseudonymization.
 * The API contract could be changed to support multiple PseudoConfig transformations, but we need to think more about
 * exactly how this should be expressed.
 *
 * @deprecated This class will be removed after the SID Mapping function is rewritten
 */
@Singleton
@Deprecated
public class PseudoConfigSplitter {

    public List<PseudoConfig> splitIfNecessary(PseudoConfig pseudoConfig) {
        PseudoConfig configWithOnlyMappingRules = filterMappingRules(pseudoConfig);

        if (configWithOnlyMappingRules.getRules().isEmpty()) {
            return List.of(pseudoConfig);
        }
        else {
            PseudoConfig replacedSidMappingRules = replaceSidMappingRules(pseudoConfig, FF31);
            return List.of(configWithOnlyMappingRules, replacedSidMappingRules);
        }
    }

    private static PseudoConfig filterMappingRules(PseudoConfig pseudoConfig) {
        PseudoConfig filteredPseudoConfig = new PseudoConfig();
        filteredPseudoConfig.setRules(pseudoConfig.getRules().stream()
                .filter(r -> r.getFunc().startsWith(MAP_SID + "("))
                .toList()
        );

        return filteredPseudoConfig;
    }

    private static String convertMapSidFuncToFf31Func(String mapSidFunc) {
        PseudoFuncDeclaration mapSidFuncDecl = PseudoFuncDeclaration.fromString(mapSidFunc);
        String keyIdParam = TinkFpeFuncConfig.Param.KEY_ID;
        LinkedHashMap args = new LinkedHashMap();
        args.put(keyIdParam, mapSidFuncDecl.getArgs().get(keyIdParam));
        args.put(TinkFpeFuncConfig.Param.UNKNOWN_CHARACTER_STRATEGY, UnknownCharacterStrategy.SKIP.name());
        return new PseudoFuncDeclaration(FF31, args).toString();
    }

    private static PseudoConfig replaceSidMappingRules(PseudoConfig pseudoConfig, String newName) {
        pseudoConfig.setRules(pseudoConfig.getRules().stream()
                .map(r -> {
                    if (r.getFunc().startsWith(MAP_SID + "(")) {
                        return new PseudoFuncRule(r.getName(), r.getPattern(), convertMapSidFuncToFf31Func(r.getFunc()));
                    }
                    else {
                        return r;
                    }
                })
                .toList()
        );

        return pseudoConfig;
    }


}
