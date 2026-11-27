# MaSyMoS Modern

This is the clean migration workspace for MaSyMoS.

MaSyMoS 1.x lives in the legacy repositories, archived and unchanged; its behaviour was migrated into these modules.

## Modules

- `masymos-core`: domain model, extractor contracts, query contracts, result DTOs.
- `masymos-neo4j`: Neo4j Java Driver implementation.
- `masymos-service`: external HTTP service replacing the old Neo4j plugin.
- `masymos-cli`: modern command line interface.

Shared test fixtures and integration-test utilities live in the individual modules' `src/test/resources`; there is no separate test-support module.

## Build

The toolchain is on `PATH` in every shell (`pwsh` 7, Maven 3.9.16, JDK 25 with
`JAVA_HOME`), so the build needs no prelude:

```powershell
mvn -q verify
```

Do not take the expected result from this file. `mvn verify` reports the gate results and the
test count it actually ran; the workspace keeps the last measured run beside the migration
documentation. Two opt-in integration lanes exist
(`external-neo4j-it`, `testcontainers-it`); on a Docker 19.03 host every JVM container needs
`--security-opt seccomp=unconfined`, which the container test and `docker-compose.yaml` set
themselves.

## Current Service Endpoints

The authoritative list — endpoints, request bodies, maintenance parameters, error codes and CLI
commands — is `docs/modernization/api-reference.md`. This file does not repeat it, because the
copy that used to live here had already drifted out of date.

Index requests use:

```json
{
  "sources": ["file:///path/to/model.sbml"]
}
```

CellML indexing additionally accepts opt-in legacy PMR CMeta enrichment:

```json
{
  "sources": ["https://models.physiomeproject.org/.../model.cellml"],
  "externalCmeta": "pmr"
}
```

Allowed values are `none` and `pmr`; omitted values default to `none`.

## Current CLI Commands

```powershell
masymos index sbml --source <uri>
masymos index cellml --source <uri> [--external-cmeta none|pmr]
masymos index sedml --source <uri>
masymos index owl --source <uri>
masymos index combine --source <uri>
masymos maintenance sedml-links
masymos maintenance annotations-index [--drop-existing] [--limit <n>] [--retry-failed] [--max-concurrency <n>]
masymos query models --keyword <term> --top 10
masymos query annotations --keyword <term> --top 10
masymos query persons --keyword <term> --top 10
masymos query publications --keyword <term> --top 10
```

## Local Operations

`modern/docker-compose.yaml` starts Neo4j plus the service built from `modern/Dockerfile` (`MASYMOS_NEO4J_PASSWORD` is required, `mvn verify` must have produced the executable service jar first). Setup, build, integration-test, and troubleshooting steps are in `docs/modernization/developer-setup.md`.

Two integration lanes exist: `external-neo4j-it` (bring your own instance) and `testcontainers-it` (starts its own Neo4j container, no external instance and no credentials). On a Docker 19.03 host every JVM container needs `--security-opt seccomp=unconfined`; `docker-compose.yaml` and `Neo4jContainersIT` already set it.

## External Neo4j Integration Tests

Routine `mvn -q verify` does not run external Neo4j integration tests. To run the opt-in profile against a disposable local test database:

```powershell
mvn -q -pl masymos-neo4j -Pexternal-neo4j-it verify `
  -Dmasymos.it.neo4j.uri=bolt://localhost:7687 `
  -Dmasymos.it.neo4j.username=<user> `
  -Dmasymos.it.neo4j.password=<password> `
  -Dmasymos.it.neo4j.database=<database>
```

The current integration tests write uniquely tagged data, verify behavior, and clean up by unique source URI or `testRunId`.
The external integration suite clears the declared disposable test instance before it starts by dropping MaSyMoS full-text indexes and deleting all nodes.
The external profile has been verified against a local Neo4j test instance for extracted SBML graph writes, real CellML/SED-ML/OWL sample graph writes, CellML creator/publication graph shape, real SBML/SED-ML cross-document post-processing links, ontology traversal queries, and full-text model query execution including field-specific clauses.
When a Neo4j instance is explicitly declared as a disposable test instance, integration tests against it may be run without a separate permission prompt.

## Current CellML Coverage

The modern CellML parser currently covers components, variables, reactions, connection direction metadata, embedded RDF/cmeta creators and publications, component comments, and reaction participant links from `<variable_ref>` / `<role>`. CellML participant roles are mapped to reaction-variable graph relationships, and `delta_variable` is preserved as `HAS_DELTA_VAR` / `IS_DELTA_VAR`.

Legacy PMR external CMeta is available through `RemotePmrCellmlExternalCmetaResolver`, which fetches `<model-uri>/@@cmeta` as PMR JSON and maps it into model annotations and publications. The default parser uses no external resolver so routine indexing does not make network calls. Service and CLI CellML indexing expose this as the explicit `externalCmeta=pmr` / `--external-cmeta pmr` option.

## Current SED-ML And OWL Coverage

The modern SED-ML parser covers model references, uniform time-course simulations with timing fields, tasks, data generators, variables with symbols and target-type classification, curves/surfaces/data sets, legacy-style output-to-model-reference `IS_ENTITY_OF` links for data-generator-backed outputs, and explicit post-processing links to indexed SBML/CellML model entities.

The modern OWL parser covers direct classes, object properties, data properties, annotation properties, direct subclass hierarchy, `someValuesFrom` restriction relationships, named individuals, class assertions, object property assertions, and data property assertions. It does not run an OWL reasoner, so inferred hierarchy/assertions beyond the explicit RDF/XML content are not generated.

## Supported Schema Versions

Version detection and matrix coverage exist for the three model formats: SBML reports `sbml.level`/`sbml.version` (Level 1 Version 1 through Level 3 Version 2 are covered by fixtures), CellML reports `cellml.version`/`cellml.namespace` (1.0, 1.1, 2.0), and SED-ML reports `sedml.level`/`sedml.version` (Level 1 Versions 1, 3 and 5). Version-specific handling: CellML 2.0 connection syntax, SBML species/compartment attributes per level, Level 3 `conversionFactor` and local parameters, and a Level 1 Version 1 species fallback because JSBML returns none for that version. Details and open gaps: `docs/modernization/model-version-matrix.md`.

## Current Query Coverage

The model-returning query path uses Neo4j full-text search over the modern `Model` aggregate node. It covers legacy-style model fields for SBML, CellML, SED-ML, publication/person metadata, model URI, annotation resource URI, and stored annotation text/non-RDF fields where those values are present in parsed annotations.

Dedicated legacy-style result endpoints are available for annotations, persons, and publications. They return the matched resource data, score, and related model URIs. They use label-specific Neo4j full-text indexes over `RESOURCE`, `PERSON`, and `PUBLICATION` nodes.

## Current Annotation Indexing Coverage

Annotation keyword enrichment is an explicit maintenance operation at `POST /api/v1/maintenance/annotations/index`. It scans `RESOURCE` nodes that are not marked indexed, resolves each durable annotation URI (direct HTTP(S), identifiers.org, MIRIAM URN) through `AnnotationUriResolver`, fetches candidates with bounded concurrency, extracts searchable text, and propagates it to the owning model for modern full-text queries.

Per-resource bookkeeping is `isIndexed`, `indexStatus`, `indexedAt`, and `indexFailure`. Unresolvable URIs are reported as skipped, transport or extraction problems as failed, so the maintenance counters keep their historical meaning.

Every run is bounded and explicit: `dropExistingIndex`, `limit` (default 500, `0` = unbounded), `retryFailed`, and `maxConcurrency` (default 4). The CLI exposes the same run as `masymos maintenance annotations-index`.

Fetched annotation page text is index-only enrichment. It exists to find annotated entities/models by ontology/provider keywords. The original annotation URI remains the durable resource identity and should not be replaced by fetched text.

The operation is not run automatically during normal indexing. Routine tests use fake resolvers and do not fetch external websites.
