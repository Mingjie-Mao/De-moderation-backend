# Moderation evaluation

_Generated 2026-08-06 by `EvaluationCommand`. Do not edit by hand._

> **These numbers measure nothing.**
> The run used the bundled starter set, which exists to prove the harness works.
> Its samples were written to exercise the code, not sampled from real posts, so
> scores on it say only whether the engine matches the terms it was configured with.
> A real dataset has to be sampled from genuine forum content and labelled by hand.

## Engine: `keyword-v1`

Dataset `evaluation/starter-samples.json`, 24 samples.

| metric | value |
|---|---|
| macro-F1 | 0.433 |
| accuracy | 0.542 |
| p50 latency | 0.229 ms |
| p95 latency | 0.460 ms |

### Per-decision

| decision | support | precision | recall | F1 |
|---|---|---|---|---|
| ALLOW | 8 | 0.538 | 0.875 | 0.667 |
| REMOVE | 11 | 0.750 | 0.545 | 0.632 |
| ESCALATE | 5 | 0.000 | 0.000 | 0.000 |

### Confusion matrix

Rows are the labelled answer, columns what the engine said.

| actual \ predicted | ALLOW | REMOVE | ESCALATE |
|---|---|---|---|
| **ALLOW** | 7 | 0 | 1 |
| **REMOVE** | 3 | 6 | 2 |
| **ESCALATE** | 3 | 2 | 0 |

### Failure modes

11 of 24 samples were judged wrongly.

| sample | category | expected | got | content | engine said |
|---|---|---|---|---|---|
| s08 | NORMAL | ALLOW | ESCALATE | Spare ticket for the revue / I have one spare ticket for Friday's revue at face value. ... | Matched a low-severity term from SPAM. |
| s12 | ABUSE | REMOVE | ALLOW | re: group project / You absolute moron, nobody wanted you in this group and nobody want... | No configured term matched. This engine cannot detect wording it has not been given. |
| s13 | ABUSE | REMOVE | ALLOW | 回复 / 你这种人真的很恶心，别再来这个版了。 | No configured term matched. This engine cannot detect wording it has not been given. |
| s14 | SPAM | REMOVE | ESCALATE | MAKE MONEY FAST / Click here for free money, limited offer, buy now before it closes. | Matched a low-severity term from SPAM. |
| s15 | SPAM | REMOVE | ESCALATE | 兼职 / 加微信 abc123，兼职日结，学生优先。 | Matched a low-severity term from SPAM. |
| s16 | SPAM | REMOVE | ALLOW | Assignment help available / Professional academic support, fast turnaround, competitive... | No configured term matched. This engine cannot detect wording it has not been given. |
| s20 | BORDERLINE | ESCALATE | REMOVE | Is this reportable? / Someone replied to my post with 'kill yourself'. Is that somethin... | Matched a high-severity term from ABUSE. |
| s21 | BORDERLINE | ESCALATE | ALLOW | that lecture / That two hour lecture was so bad I wanted to die. | No configured term matched. This engine cannot detect wording it has not been given. |
| s22 | BORDERLINE | ESCALATE | REMOVE | banter / My friend called me an idiot for forgetting the meeting, which honestly is fair. | Matched a high-severity term from ABUSE. |
| s23 | BORDERLINE | ESCALATE | ALLOW | rules question / Is it against the rules to sell my own lecture notes to another student? | No configured term matched. This engine cannot detect wording it has not been given. |
| s24 | BORDERLINE | ESCALATE | ALLOW | 吐槽 / 这门课的作业量真的让人想死，每周都写不完。 | No configured term matched. This engine cannot detect wording it has not been given. |

## Reproducing this

```bash
set -a && . ./.env && set +a && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--campusguard.evaluation.run=true \
  --campusguard.evaluation.dataset=docs/evaluation-samples.json"
```

Omitting `--campusguard.evaluation.dataset` uses the bundled starter set.
