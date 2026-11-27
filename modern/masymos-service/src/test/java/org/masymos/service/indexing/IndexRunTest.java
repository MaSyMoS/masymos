package org.masymos.service.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.IndexedModel;
import org.masymos.core.extract.ModelParseException;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.service.api.StorageUnavailableException;

/**
 * The run loop of {@code C04-1} (finding {@code GAP02-002b}, decision 49): a per-document failure does
 * not stop the run, a store outage does, and every source of the request appears in the answer exactly
 * once, in request order.
 *
 * <p>Every case would fail if the loop went back to one call over the whole list: nothing after the
 * first failure would be indexed and no outcome would exist at all.
 */
class IndexRunTest {
    private static final URI FIRST = URI.create("file:///models/first.sbml");
    private static final URI SECOND = URI.create("file:///models/second.sbml");
    private static final URI THIRD = URI.create("file:///models/third.sbml");

    @Test
    void aParseFailureDoesNotStopTheRunAndTheOutcomesNameEverySource() {
        var seen = new ArrayList<URI>();

        var failure = assertThrows(PartialIndexException.class, () -> IndexRun.run(
                List.of(FIRST, SECOND, THIRD),
                uri -> {
                    seen.add(uri);
                    if (uri.equals(SECOND)) {
                        throw new ModelParseException("not a model: " + uri, null);
                    }
                    return List.of(indexed(uri));
                },
                IndexRun.ownDocuments()));

        assertEquals(List.of(FIRST, SECOND, THIRD), seen, "every source is attempted, in request order");
        var outcomes = failure.outcomes();
        assertEquals(3, outcomes.size());
        assertEquals(SourceOutcome.Status.INDEXED, outcomes.get(0).status());
        assertEquals("uid/models/first.sbml", outcomes.get(0).documents().getFirst().uid());
        assertEquals(SourceOutcome.Status.FAILED, outcomes.get(1).status());
        assertTrue(outcomes.get(1).detail().contains("ModelParseException"),
                "the reason names the failure's kind: " + outcomes.get(1).detail());
        assertEquals(SourceOutcome.Status.INDEXED, outcomes.get(2).status(),
                "a later source is still indexed");
        assertTrue(failure.getMessage().contains("2 of 3 sources were indexed"),
                "the detail is a count a caller can read: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(SECOND.toString()),
                "it names the first failure: " + failure.getMessage());
    }

    @Test
    void anUnreadableSourceIsAFailureLikeAnUnparsableOne() {
        var failure = assertThrows(PartialIndexException.class, () -> IndexRun.run(
                List.of(FIRST),
                uri -> {
                    throw new IOException("cannot read " + uri);
                },
                IndexRun.ownDocuments()));

        assertEquals(SourceOutcome.Status.FAILED, failure.outcomes().getFirst().status());
        assertTrue(failure.outcomes().getFirst().detail().contains("cannot read"));
    }

    @Test
    void aStoreOutageEndsTheRunAndTheRestIsNotAttempted() {
        var seen = new ArrayList<URI>();

        var failure = assertThrows(PartialIndexException.class, () -> IndexRun.run(
                List.of(FIRST, SECOND, THIRD),
                uri -> {
                    seen.add(uri);
                    if (uri.equals(SECOND)) {
                        throw new StorageUnavailableException("bolt://neo4j:secret@db.internal:7687 refused");
                    }
                    return List.of(indexed(uri));
                },
                IndexRun.ownDocuments()));

        assertEquals(List.of(FIRST, SECOND), seen, "the run stops at the outage");
        var outcomes = failure.outcomes();
        assertEquals(3, outcomes.size(), "the sources nobody read are still named");
        assertEquals(SourceOutcome.Status.FAILED, outcomes.get(1).status());
        assertEquals(SourceOutcome.STORAGE_UNAVAILABLE_DETAIL, outcomes.get(1).detail(),
                "the driver's message must not be published: it can name a bolt URI");
        assertEquals(SourceOutcome.Status.NOT_ATTEMPTED, outcomes.get(2).status());
        assertNull(outcomes.get(2).detail(), "a source nobody read gets no invented reason");
    }

    @Test
    void aStoreOutageOnTheFirstSourceKeepsTheDocumentedStorageAnswer() {
        var outage = new StorageUnavailableException("model storage is disabled");

        var thrown = assertThrows(StorageUnavailableException.class, () -> IndexRun.run(
                List.of(FIRST, SECOND),
                uri -> {
                    throw outage;
                },
                IndexRun.ownDocuments()));

        assertSame(outage, thrown, "nothing was written, so the answer is the 503, not a partial report");
    }

    @Test
    void aCompleteRunReturnsEveryResultInRequestOrderAndThrowsNothing() {
        var results = IndexRun.runModels(
                List.of(FIRST, SECOND),
                uri -> List.of(indexed(uri), indexed(uri)));

        assertEquals(4, results.size(), "both sources, both documents each, flattened in request order");
        assertEquals("uid/models/first.sbml", results.getFirst().identifier().uid());
    }

    @Test
    void aRuntimeFailureThatIsNotADocumentOrStoreProblemPropagates() {
        var defect = new IllegalStateException("the writer refused a label");

        var thrown = assertThrows(IllegalStateException.class, () -> IndexRun.run(
                List.of(FIRST),
                uri -> {
                    throw defect;
                },
                IndexRun.ownDocuments()));

        assertSame(defect, thrown, "absorbing it into 'this document failed' would hide the defect");
    }

    private static IndexedModel indexed(URI sourceUri) {
        var fileId = "file" + sourceUri.getPath();
        return new IndexedModel(
                new ModelSource(new ModelReference(fileId, sourceUri, ModelType.SBML)),
                new ModelIdentifier("uid" + sourceUri.getPath(), fileId));
    }
}
