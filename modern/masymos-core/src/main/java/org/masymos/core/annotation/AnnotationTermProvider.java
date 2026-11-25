package org.masymos.core.annotation;

import java.util.Collection;
import java.util.Map;

/**
 * One source of ontology term definitions — BioPortal, OLS4, or any later adapter.
 *
 * <p>Batch-shaped on purpose: batching is an adapter detail, so an adapter may chunk by its own
 * maximum batch size or resolve one term per request without the resolver knowing the difference.
 * The resolver asks a provider at most once per call, with every pending key the provider
 * {@link #supports(TermKey) supports}.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@link #source()} never changes for an instance; the resolver records it on every definition
 *       that came from this provider.
 *   <li>{@link #supports(TermKey)} answers from configuration and the prefix catalogue, never from
 *       I/O.
 *   <li>{@link #resolveAll(Collection)} never throws. It returns exactly one entry for every key it
 *       accepted, mapping each key to what it learned: a resolved definition, a
 *       {@link TermDefinition.FailureClass#DEFINITIVE} "no such class", or a
 *       {@link TermDefinition.FailureClass#TRANSIENT} failure worth retrying. A provider that throws
 *       is demoted by the resolver for the rest of the run, and its keys are recorded transient.
 * </ul>
 */
public interface AnnotationTermProvider {

    /** The source this provider answers from; recorded on every definition it returns. */
    TermDefinition.Source source();

    /** Whether this provider serves the prefix of {@code key}. Pure: no I/O, never throws. */
    boolean supports(TermKey key);

    /**
     * Resolves one batch of keys. Never throws, and returns an entry for every key it accepted.
     */
    Map<TermKey, TermDefinition> resolveAll(Collection<TermKey> keys);
}
