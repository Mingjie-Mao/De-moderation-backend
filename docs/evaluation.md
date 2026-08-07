# Moderation engine benchmark

_Generated 2026-08-07 by `EvaluationCommand`. Do not edit by hand._

> **These numbers measure nothing.**
> The run used the bundled starter set, which exists to prove the harness works.
> Its samples were written to exercise the code, not sampled from real posts, so
> scores on it say only whether an engine matches the terms it was configured with.
> A real dataset has to be sampled from genuine forum content and labelled by hand.

Dataset `evaluation/starter-samples.json`, 24 samples.

## What this dataset is

| expected action | from the app | written for this | total |
|---|---|---|---|
| ALLOW | 0 | 8 | 8 |
| REMOVE | 0 | 11 | 11 |
| ESCALATE | 0 | 5 | 5 |

`from the app` is text taken verbatim from the seeded content of the
De-discussion campus forum: the right register, the right two languages, and
written with no engine in mind. It is also almost entirely benign, because
nobody seeds a demo application with abuse.

`written for this` is the rest, and it is the weakest part of the dataset. It
measures an engine against one person's idea of what a violation looks like.
The violating classes are made of it because there was no honest alternative.
Most of it deliberately avoids the wording in the rule term lists, since an
engine that only has to recognise the words it was configured with is being
asked nothing.

The two are scored separately below. A wide gap means the authored half is
easier than the real half and the headline number is flattering by that much.

## Side by side

| engine | status | accuracy | macro-F1 | macro-P | macro-R | errors | mean | p50 | p95 | tokens | est. cost |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `gemini-v1` | DEGRADED | 1.000 | 1.000 | 1.000 | 1.000 | 14 | 2613.386 ms | 2294.035 ms | 4230.590 ms | 2899 + 419 | not priced |

An engine that answered nothing scores zero on every column, which on a table
is indistinguishable from an engine that answered everything wrongly. The
status column is there so those two are never confused.

## `gemini-v1` in detail

> 14 of 24 samples failed outright. Everything below covers only the 10 it answered.

| decision | support | precision | recall | F1 |
|---|---|---|---|---|
| ALLOW | 6 | 1.000 | 1.000 | 1.000 |
| REMOVE | 4 | 1.000 | 1.000 | 1.000 |

Rows are the labelled answer, columns what the engine said.

| actual \ predicted | ALLOW | REMOVE |
|---|---|---|
| **ALLOW** | 6 | 0 |
| **REMOVE** | 0 | 4 |

No mistakes on this dataset.

## On real traffic

No model calls have been recorded yet.

## Reproducing this

```bash
set -a && . ./.env && set +a && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--campusguard.evaluation.run=true \
  --campusguard.evaluation.dataset=docs/evaluation-samples.json"
```

Omitting `--campusguard.evaluation.dataset` uses the bundled starter set. Every
registered engine runs; one that is unavailable is reported and skipped
rather than ending the run. Machine-readable output lands beside this file as
`evaluation.json` and `evaluation-samples.csv`.

Token prices are not built in, because a plausible default would put a number
in this report that nobody checked. Configure them per engine under
`campusguard.evaluation.pricing` to fill in the cost column.
