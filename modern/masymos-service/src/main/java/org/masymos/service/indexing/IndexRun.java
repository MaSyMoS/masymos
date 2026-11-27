package org.masymos.service.indexing;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.masymos.core.archive.CombineArchiveException;
import org.masymos.core.extract.IndexedModel;
import org.masymos.core.extract.ModelParseException;
import org.masymos.service.api.StorageUnavailableException;

/**
 * Runs one index request source by source and reports what every source did (task {@code C04-1},
 * finding {@code GAP02-002b}, decision 49).
 *
 * <p>The service layer used to hand the whole list to one call that stopped at the first failure, so the
 * documents before it were written and invisible. Three rules replace that:
 *
 * <ul>
 *   <li>A failure that is a fact about <em>one document</em> - unreadable ({@link IOException}),
 *       unparsable ({@link ModelParseException}) or an archive that cannot be opened
 *       ({@link CombineArchiveException}) - does not stop the run. The sources are independent, and
 *       doing nine of ten is better than doing none, as long as the answer says which one is missing.
 *   <li>A failure that is a fact about the <em>store</em> ({@link StorageUnavailableException}) stops the
 *       run: every remaining source would fail for the same reason, and a hundred identical driver
 *       failures are noise, not a report. The sources nobody read are marked {@code NOT_ATTEMPTED}
 *       rather than given a reason that was never observed. When it hits the <em>first</em> source it is
 *       rethrown instead, because nothing has been written yet: that is the documented {@code 503
 *       storage-unavailable}, and a caller must be able to tell "retry the request" from "some documents
 *       are already written".
 *   <li>Any other runtime failure is not an index outcome but a defect of the surface, and it is left to
 *       propagate: absorbing a {@code NullPointerException} into "this document failed" would hide it.
 * </ul>
 *
 * <p>Every source in the request appears in the answer exactly once, in request order, so a caller can
 * retry by filtering {@code FAILED} and {@code NOT_ATTEMPTED} without diffing names.
 */
public final class IndexRun {

    /** One source's work, as the service call for a single source URI. */
    @FunctionalInterface
    public interface Step<T> {
        T index(URI sourceUri) throws IOException;
    }

    /** One source's work for the model languages, whose result is the documents it wrote. */
    @FunctionalInterface
    public interface ModelStep {
        List<IndexedModel> index(URI sourceUri) throws IOException;
    }

    private IndexRun() {}

    /**
     * The reading for a step whose result <em>is</em> its document list, which is every model language:
     * only a COMBINE archive returns a result record of its own that has to be asked for its models.
     */
    public static Function<List<IndexedModel>, List<IndexedModel>> ownDocuments() {
        return models -> models;
    }

    /**
     * Indexes every source and returns the written models, flattened in request order - the shape the
     * four model-language endpoints answer with.
     */
    public static List<IndexedModel> runModels(List<URI> sources, ModelStep step) {
        return run(sources, step::index, ownDocuments()).stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * Indexes every source and returns the step's results that were produced.
     *
     * @param sources the request's sources, in the order the caller named them
     * @param step the per-source work; it must index exactly the one URI it is given
     * @param documents how to read the written models out of the step's result, for the outcome report
     * @return one result per indexed source, in request order
     * @throws PartialIndexException when at least one source was not indexed; it carries the outcome of
     *     every source, so nothing that was written is lost from the answer
     */
    public static <T> List<T> run(
            List<URI> sources,
            Step<T> step,
            Function<T, List<IndexedModel>> documents
    ) {
        var results = new ArrayList<T>();
        var outcomes = new ArrayList<SourceOutcome>();
        var complete = true;
        for (var position = 0; position < sources.size(); position++) {
            var sourceUri = sources.get(position);
            try {
                var result = step.index(sourceUri);
                results.add(result);
                outcomes.add(SourceOutcome.indexed(sourceUri, documents.apply(result)));
            } catch (StorageUnavailableException outage) {
                if (position == 0) {
                    // The store was gone before this run wrote anything: that is the documented 503
                    // storage-unavailable, not a report about a partial run. A caller has to be able to
                    // tell "nothing happened, retry the request" from "some documents are written".
                    throw outage;
                }
                outcomes.add(new SourceOutcome(
                        sourceUri,
                        SourceOutcome.Status.FAILED,
                        List.of(),
                        SourceOutcome.STORAGE_UNAVAILABLE_DETAIL));
                addNotAttempted(sources, position + 1, outcomes);
                complete = false;
                break;
            } catch (IOException | ModelParseException | CombineArchiveException failure) {
                outcomes.add(SourceOutcome.failed(sourceUri, failure));
                complete = false;
            }
        }
        if (!complete) {
            throw new PartialIndexException(List.copyOf(outcomes));
        }
        return List.copyOf(results);
    }

    private static void addNotAttempted(List<URI> sources, int from, List<SourceOutcome> outcomes) {
        for (var position = from; position < sources.size(); position++) {
            outcomes.add(SourceOutcome.notAttempted(sources.get(position)));
        }
    }
}
