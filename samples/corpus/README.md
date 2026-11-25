# Annotation census corpus

Real inputs for the annotation keyword work. The census that this corpus fed is summarised in
the workspace's migration documentation; the numbers there are citable, the files here are the
raw material for re-running it.

## What is tracked and what is not

| Path | Tracked | Why |
| --- | --- | --- |
| `MANIFEST.tsv` | yes | One row per model: source, id, URL, byte size where known, SHA-256 where the file was hashed |
| `fetch.ps1` | yes | Rebuilds the corpus from the manifest and verifies every hash it knows |
| `biomodels/`, `pmr/`, `jws/` | **no** (gitignored) | ~25 MB of third-party binaries; reproducible from the manifest instead of bloating every clone |

## Contents

- `biomodels/` — 30 curated BioModels SBML files (`BIOMD…_url.xml`), the files whose annotation mass the census measured.
- `pmr/` — the 18 CellML models from the Physiome Model Repository that carry annotations, stored per
  workspace (`pmr/w/<owner>/<workspace>@<changeset>/<file>`) because different workspaces reuse leaf
  names such as `model.cellml` and `units.cellml`. Twelve further models were fetched during the census
  and carry none; they are not listed because the only list of them was unreliable, and a manifest must
  be something you can verify.
- `jws/` — 30 JWS Online `.sedx` COMBINE archives from the SED-ML test suite. Each bundles its own model
  (109 internal model references, 0 external), and 28 of the 30 carry only archive-format metadata, not
  biology.

## Use

```powershell
cd samples\corpus
.\fetch.ps1                # fetch what is missing
.\fetch.ps1 -Source pmr    # one source
.\fetch.ps1 -Force         # re-download and re-verify
```

The script skips files that are present and match their manifest hash, warns on a mismatch instead of
overwriting silently, and reports `downloaded`, `skipped` and `failed` counts. Nothing it writes is
tracked by git.

## Provenance

All files were retrieved on 2026-11-13 from the URLs in the manifest: BioModels (`https://www.ebi.ac.uk/biomodels/`),
PMR (`https://models.physiomeproject.org/`) and the SED-ML test suite
(`https://github.com/SED-ML/sedml-test-suite`, `archives/jws/omex`). Third-party licences apply to each file;
none of them is MaSyMoS material.
