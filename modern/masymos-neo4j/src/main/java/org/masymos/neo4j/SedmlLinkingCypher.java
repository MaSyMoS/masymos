package org.masymos.neo4j;

import java.util.Map;

public final class SedmlLinkingCypher {
    private SedmlLinkingCypher() {
    }

    /**
     * Links a SED-ML model reference to the SBML model it names.
     *
     * <p>The two sides spell the same identity differently, which is why a plain equality never
     * worked and a 15-character suffix was used instead - a suffix match links any two models whose
     * URIs happen to end alike. Measured on the corpus:
     *
     * <ul>
     *   <li>{@code SEDML_MODELREFERENCE.modelSource} is a MIRIAM URN, {@code
     *       urn:miriam:biomodels.db:BIOMD0000000005}, in 35 of 38 sample references; the rest are
     *       relative hrefs and one HTTP URL.
     *   <li>{@code RESOURCE.uri} on an {@code :is} annotation is the raw RDF value, {@code
     *       http://identifiers.org/biomodels.db/BIOMD0000000005}, stored unnormalised by
     *       {@code ParsedModelGraphMapper}.
     *   <li>A relative {@code source} such as {@code model1.xml} is additionally resolved against the
     *       document URI into {@code resolvedSource} by {@code SedmlModelParser}; an absolute MIRIAM
     *       URN never gets one, because it needs no resolution.
     * </ul>
     *
     * <p>Two forms link. A source that is literally equal to the annotation resource links as it is,
     * which is what covers an archive entry (a {@code <archive>!/<entry>} URI) or a plain URI that the
     * document declares and the model carries. Otherwise both sides are reduced to the identity
     * {@code prefix:identifier} - for a MIRIAM URN the text after {@code urn:miriam:}, for an
     * identifiers.org URL the path with {@code /} read as {@code :} - and only an identity equality
     * links. Two models that share a URI suffix but no identity stay apart; no fragment of either side
     * can match by accident. The resolved source is tried first because it is the value the document
     * points at, the declared source second.
     */
    public static PreparedGraphWrite linkModelReferences() {
        return new PreparedGraphWrite("""
                MATCH (ref:SEDML_MODELREFERENCE)
                WITH coalesce(ref.resolvedSource, ref.modelSource) AS source, ref
                WHERE source IS NOT NULL
                MATCH (model:SBML_MODEL)-[:HAS_ANNOTATION]->()-[:is]->(resource:RESOURCE)
                WITH ref, model, source, resource.uri AS target
                WITH ref, model, source, target,
                  CASE
                    WHEN source STARTS WITH 'urn:miriam:' THEN substring(source, 11)
                    WHEN source CONTAINS '://identifiers.org/' THEN replace(split(source, '://identifiers.org/')[1], '/', ':')
                    ELSE null
                  END AS sourceIdentity,
                  CASE
                    WHEN target STARTS WITH 'urn:miriam:' THEN substring(target, 11)
                    WHEN target CONTAINS '://identifiers.org/' THEN replace(split(target, '://identifiers.org/')[1], '/', ':')
                    ELSE null
                  END AS targetIdentity
                WHERE source = target
                   OR (sourceIdentity IS NOT NULL AND sourceIdentity = targetIdentity AND sourceIdentity <> '')
                CREATE (ref)-[relationship:REFERENCES_SIMULATION_MODEL]->(model)
                RETURN count(relationship) AS relationshipCount
                """, Map.of());
    }

    public static PreparedGraphWrite linkSbmlObservedVariables() {
        return new PreparedGraphWrite("""
                MATCH (variable:SEDML_VARIABLE)-[:CALCULATES_MODEL]->()-[:REFERENCES_SIMULATION_MODEL]->()-[:HAS_SPECIES]->(species)
                WHERE variable.target IS NOT NULL AND variable.target =~ ('.*id=\\'' + species.id + '\\']')
                CREATE (variable)-[relationship:OBSERVES]->(species)
                RETURN count(relationship) AS relationshipCount
                """, Map.of());
    }

    public static PreparedGraphWrite linkCellmlObservedVariables() {
        return new PreparedGraphWrite("""
                MATCH (variable:SEDML_VARIABLE)-[:CALCULATES_MODEL]->()-[:REFERENCES_SIMULATION_MODEL]->()-[:HAS_COMPONENT]->(component)-[:HAS_VARIABLE]->(cellmlVariable)
                WHERE variable.target IS NOT NULL
                  AND variable.target =~ ('.*component.*name=\\'' + component.name + '\\']')
                  AND variable.target =~ ('.*variable.*name=\\'' + cellmlVariable.name + '\\']')
                CREATE (variable)-[relationship:OBSERVES]->(cellmlVariable)
                RETURN count(relationship) AS relationshipCount
                """, Map.of());
    }

    /**
     * Links a {@code SEDML_CHANGE} to the entity its {@code target} names, inside the model the change
     * declares ({@code OPN-3}, 2026-11-26).
     *
     * <p>Only an entity the model already contains can be linked: {@code changeAttribute} and
     * {@code computeChange} name one through their target XPath, while {@code addXML}, {@code removeXML}
     * and {@code changeXML} carry the element in their payload - and an added element usually does not
     * exist in the model yet - so those three keep it as {@code payloadEntityType}/{@code payloadEntityId}
     * properties and are deliberately not linked here.
     *
     * <p>The match is the idiom {@link #linkSbmlObservedVariables()} already uses, for the same reason:
     * a SED-ML target is an XPath, and the entity id inside it is the only comparable part. The statement
     * is one fixed text whatever the document contains, so a document with one change and one with a
     * thousand produce one statement each, not one per change.
     */
    public static PreparedGraphWrite linkChangedEntities() {
        return new PreparedGraphWrite("""
                MATCH (change:SEDML_CHANGE)
                WHERE change.target IS NOT NULL AND change.modelReference IS NOT NULL
                MATCH (reference:SEDML_MODELREFERENCE {id: change.modelReference})
                      -[:REFERENCES_SIMULATION_MODEL]->(model)
                MATCH (model)-[:HAS_SPECIES|HAS_REACTION|HAS_COMPARTMENT|HAS_PARAMETER]->(target)
                WHERE change.target =~ ('.*id=\\'' + target.id + '\\']')
                CREATE (change)-[relationship:CHANGES_ENTITY]->(target)
                RETURN count(relationship) AS relationshipCount
                """, Map.of());
    }
}
