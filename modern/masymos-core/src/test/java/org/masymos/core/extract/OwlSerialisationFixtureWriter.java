package org.masymos.core.extract;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Collectors;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Regenerates the derived fixtures of {@code owl/four-serialisations/} from the canonical
 * {@code sbo.ofn}. Not a test: it exists so the three other serialisations are a real conversion by
 * the same library the extractor uses, instead of three hand translations that drift apart. Run it
 * after changing {@code sbo.ofn}, then commit the result.
 *
 * <p>Usage: {@code OwlSerialisationFixtureWriter <directory containing sbo.ofn>}
 */
public final class OwlSerialisationFixtureWriter {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("usage: OwlSerialisationFixtureWriter <directory containing sbo.ofn>");
            return;
        }
        var directory = Path.of(args[0]);
        var canonical = directory.resolve("sbo.ofn");
        var manager = OWLManager.createOWLOntologyManager();
        // Same offline rule as the converter: a fixture that ever declares an import must not make the
        // generator touch the network.
        var scratch = new ArrayList<Path>();
        manager.getIRIMappers().clear();
        manager.getIRIMappers().add(OwlSerialisationConverter.importMapper(scratch));
        try {
            var ontology = manager.loadOntologyFromOntologyDocument(new File(canonical.toString()));
            System.out.println("canonical " + canonical.getFileName()
                    + " axioms=" + ontology.getAxiomCount()
                    + " classes=" + ontology.getClassesInSignature().size()
                    + " individuals=" + ontology.getIndividualsInSignature().size());
            write(manager, ontology, directory.resolve("sbo.rdf"), new RDFXMLDocumentFormat());
            write(manager, ontology, directory.resolve("sbo.ttl"), new TurtleDocumentFormat());
            write(manager, ontology, directory.resolve("sbo.owlxml"), new OWLXMLDocumentFormat());
        } finally {
            for (var file : scratch) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static void write(OWLOntologyManager manager, OWLOntology ontology, Path target,
            OWLDocumentFormat format) throws Exception {
        try (OutputStream out = Files.newOutputStream(target)) {
            manager.saveOntology(ontology, format, out);
        }
        // OWLAPI's renderers use the platform line separator and pad with trailing spaces, so on
        // Windows the Turtle fixture comes out as CRLF. RepositoryEncodingTest fails the build on a
        // carriage return, and `git diff --check` complains about the whitespace, so both are
        // normalised here instead of by hand afterwards.
        var content = Files.readString(target, StandardCharsets.UTF_8);
        var normalised = content.replace("\r\n", "\n").lines()
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n")) + "\n";
        Files.writeString(target, normalised, StandardCharsets.UTF_8);
        System.out.println("wrote " + target.getFileName()
                + " bytes=" + Files.size(target)
                + " axioms=" + ontology.getAxiomCount());
    }
}
