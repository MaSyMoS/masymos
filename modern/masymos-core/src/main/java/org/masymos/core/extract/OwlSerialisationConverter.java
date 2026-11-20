package org.masymos.core.extract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.masymos.core.model.ModelReference;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSourceBase;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

/**
 * Converts an OWL document in Turtle, OWL/XML or functional syntax into RDF/XML, so that one reader
 * builds the graph for all four OWL 2 serialisations (OWL-2).
 *
 * <p>The conversion must not read anything the caller did not supply (decisions 17 and 19): an
 * {@code owl:imports} is never fetched, and a sibling file a relative import points at is never
 * opened. OWLAPI resolves imports through its IRI mappers, and leaving them empty is not enough —
 * it then tries the import IRI itself and blocks for the TCP timeout. Every requested IRI is
 * therefore mapped to a throw-away local document that declares <em>that IRI</em> as an empty
 * ontology. Declaring the IRI matters: an IRI-less placeholder makes OWLAPI drop the
 * {@code owl:imports} triple from the output, which would silently lose part of the document.
 */
final class OwlSerialisationConverter {
    private static final String PLACEHOLDER =
            """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#">
              <owl:Ontology rdf:about="%s"/>
            </rdf:RDF>
            """;

    byte[] toRdfXml(byte[] content, OwlSerialisation serialisation, ModelReference reference) {
        var scratch = new ArrayList<Path>();
        try {
            var manager = OWLManager.createOWLOntologyManager();
            manager.getIRIMappers().clear();
            manager.getIRIMappers().add(importMapper(scratch));
            var configuration = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setFollowRedirects(false)
                    .setLoadAnnotationAxioms(true);
            // Strict loading is deliberately NOT enabled: measured 2026-11-16, it rejects a valid
            // Turtle document that declares imports ("unparsable"), while genuine syntax errors fail
            // without it. Non-strict does tolerate unknown OWL/XML elements, which is OWLAPI's
            // documented behaviour and not something this converter can improve on.
            var source = sourceOf(content, IRI.create(reference.sourceUri()));
            var ontology = manager.loadOntologyFromOntologyDocument(source, configuration);
            var rdfXml = new ByteArrayOutputStream();
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), rdfXml);
            return rdfXml.toByteArray();
        } catch (OWLOntologyCreationException | OWLOntologyStorageException | RuntimeException failure) {
            // Deliberately not `catch (Exception)`: a NullPointerException or an OWLAPI misuse must not
            // be reported as "this document could not be converted", or a bug reads like a bad file.
            throw new ModelParseException(serialisation.displayName()
                    + " could not be converted to RDF/XML: " + reference.sourceUri(), failure);
        } finally {
            for (var file : scratch) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // Scratch files carry no information; a leftover temp file must not fail a parse.
                }
            }
        }
    }

    /**
     * A document source that can be read more than once. OWLAPI tries several parsers on one source
     * and the single-use {@code StreamDocumentSource} hands the second attempt a dead stream, which
     * in strict mode surfaces as "unparsable" for a document that is perfectly valid.
     */
    private static OWLOntologyDocumentSource sourceOf(byte[] content, IRI documentIri) {
        return new OWLOntologyDocumentSourceBase(documentIri, null, null) {
            @Override
            public Optional<InputStream> getInputStream() {
                return Optional.of(new ByteArrayInputStream(content));
            }
        };
    }

    /**
     * The mapper that keeps a conversion offline: any import OWLAPI asks for resolves to a scratch
     * document, never to the requested IRI. Exposed package-private so a test can assert the
     * mechanism itself — the graph cannot show it, because the emitted RDF/XML renders the root
     * ontology only, so an axiom from an import would never appear either way.
     */
    static OWLOntologyIRIMapper importMapper(List<Path> scratch) {
        return ontologyIri -> placeholderFor(ontologyIri, scratch);
    }

    private static IRI placeholderFor(IRI ontologyIri, List<Path> scratch) {
        try {
            var file = Files.createTempFile("masymos-owl-import", ".owl");
            scratch.add(file);
            Files.writeString(file, PLACEHOLDER.formatted(escape(ontologyIri.toString())),
                    StandardCharsets.UTF_8);
            return IRI.create(file.toUri());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String escape(String iri) {
        return iri.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
