package org.masymos.core.model;

/**
 * What the store holds, as counts. Flat numbers rather than nested maps, because this is a report for
 * an operator and the CLI prints it as it arrives.
 *
 * <p>{@code documents} counts versions (a {@code Document} node is one stored version),
 * {@code models} counts the extracted model graphs, and the per-kind counts use the labels the query
 * surface filters on ({@code :SBML_MODEL} and friends), so a statistic and a filtered query agree.</p>
 */
public record StoreStatistics(
        long documents,
        long models,
        long sbmlModels,
        long cellmlModels,
        long sedmlModels,
        long owlModels,
        long persons,
        long publications,
        long annotations,
        long archives) {
}
