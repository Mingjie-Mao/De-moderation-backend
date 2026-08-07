# Moderation engine benchmark

_Generated 2026-08-07 by `EvaluationCommand`. Do not edit by hand._

Dataset `evaluation-samples.json`, 192 samples.

## What this dataset is

| expected action | from the app | written for this | total |
|---|---|---|---|
| ALLOW | 90 | 0 | 90 |
| REMOVE | 0 | 66 | 66 |
| ESCALATE | 2 | 34 | 36 |

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
| `keyword-v1` | OK | 0.505 | 0.286 | 0.403 | 0.369 | 0 | 0.084 ms | 0.057 ms | 0.168 ms | 0 | $0 |
| `gemini-v1` | **UNAVAILABLE** | — | — | — | — | 0 | — | — | — | — | — |

`gemini-v1` answered nothing: No engine named 'gemini-v1'. Available: [keyword-v1]

An engine that answered nothing scores zero on every column, which on a table
is indistinguishable from an engine that answered everything wrongly. The
status column is there so those two are never confused.

## `keyword-v1` in detail

| decision | support | precision | recall | F1 |
|---|---|---|---|---|
| ALLOW | 90 | 0.508 | 1.000 | 0.674 |
| REMOVE | 66 | 0.700 | 0.106 | 0.184 |
| ESCALATE | 36 | 0.000 | 0.000 | 0.000 |

Scored separately by where the samples came from:

| samples | n | accuracy | macro-F1 |
|---|---|---|---|
| from the app | 92 | 0.978 | 0.495 |
| written for this | 100 | 0.070 | 0.061 |

These two rows are **not comparable**. Each source is dominated by a single
expected answer — the real content is almost all ALLOW, the written content
almost all violations — so the difference between them measures the class
mix rather than the difficulty. An engine that answered ALLOW to everything
would score near 1.000 on the real half and near 0.000 on the written half
without knowing anything.

Fixing this needs violating content drawn from real traffic, which a seeded
demo application does not contain. It is the honest limit of this dataset.

Rows are the labelled answer, columns what the engine said.

| actual \ predicted | ALLOW | REMOVE | ESCALATE |
|---|---|---|---|
| **ALLOW** | 90 | 0 | 0 |
| **REMOVE** | 54 | 7 | 5 |
| **ESCALATE** | 33 | 3 | 0 |

95 of 192 answered samples were judged wrongly.

| sample | category | expected | got | content | engine said |
|---|---|---|---|---|---|
| s091 | BORDERLINE | ESCALATE | ALLOW | Stop overthinking it and just copy the layout. | No configured term matched. This engine cannot detect wording it has not been given. |
| s092 | BORDERLINE | ESCALATE | ALLOW | I write my own notes first, then ask AI to find gaps. That keeps me from being carried ... | No configured term matched. This engine cannot detect wording it has not been given. |
| s093 | ABUSE | REMOVE | ALLOW | You contribute nothing to this group and everyone in the tutorial knows it. | No configured term matched. This engine cannot detect wording it has not been given. |
| s094 | ABUSE | REMOVE | ALLOW | Honestly just drop out, you are wasting a place someone competent could have. | No configured term matched. This engine cannot detect wording it has not been given. |
| s095 | ABUSE | REMOVE | ALLOW | Nobody in this cohort can stand you. Take the hint and stop posting. | No configured term matched. This engine cannot detect wording it has not been given. |
| s096 | ABUSE | REMOVE | ALLOW | Every time you open your mouth in class the whole room cringes. Pathetic. | No configured term matched. This engine cannot detect wording it has not been given. |
| s099 | ABUSE | REMOVE | ALLOW | Whoever wrote this is functionally illiterate. Learn English before posting. | No configured term matched. This engine cannot detect wording it has not been given. |
| s100 | ABUSE | REMOVE | ALLOW | I hope you fail this course. You deserve it for how you treated the group. | No configured term matched. This engine cannot detect wording it has not been given. |
| s101 | ABUSE | REMOVE | ALLOW | Stop pretending you did any work. You are a parasite on this team. | No configured term matched. This engine cannot detect wording it has not been given. |
| s102 | ABUSE | REMOVE | ALLOW | Your accent makes you impossible to understand, maybe stay quiet in tutorials. | No configured term matched. This engine cannot detect wording it has not been given. |
| s103 | ABUSE | REMOVE | ALLOW | Keep crying about the marks, it is the only thing you are good at. | No configured term matched. This engine cannot detect wording it has not been given. |
| s104 | ABUSE | REMOVE | ALLOW | Get out of this forum, you are worthless here. | No configured term matched. This engine cannot detect wording it has not been given. |
| s105 | ABUSE | REMOVE | ALLOW | 楼主脑子进水了吧，说的都是什么垃圾东西。 | No configured term matched. This engine cannot detect wording it has not been given. |
| s106 | ABUSE | REMOVE | ALLOW | 你这种人就该被踢出小组，什么都不做还好意思发言。 | No configured term matched. This engine cannot detect wording it has not been given. |
| s109 | ABUSE | REMOVE | ALLOW | 就你这水平也敢来讨论？回去重修吧，别浪费大家时间。 | No configured term matched. This engine cannot detect wording it has not been given. |
| s110 | ABUSE | REMOVE | ALLOW | 长得丑还爱出风头，真是服了。 | No configured term matched. This engine cannot detect wording it has not been given. |
| s111 | ABUSE | REMOVE | ALLOW | 你们组那个人真的又懒又蠢，谁跟他一组谁倒霉。 | No configured term matched. This engine cannot detect wording it has not been given. |
| s112 | ABUSE | REMOVE | ALLOW | 有些人就是又菜又爱玩，说的就是楼上。 | No configured term matched. This engine cannot detect wording it has not been given. |
| s113 | ABUSE | REMOVE | ALLOW | She only got the internship because of who she knows. Everyone can see it. | No configured term matched. This engine cannot detect wording it has not been given. |
| s114 | ABUSE | REMOVE | ALLOW | 这个助教根本不懂装懂，上课就是在浪费我们的钱。 | No configured term matched. This engine cannot detect wording it has not been given. |
| s115 | ABUSE | REMOVE | ALLOW | Anyone else think the international students in this course are dragging the average down? | No configured term matched. This engine cannot detect wording it has not been given. |
| s116 | ABUSE | REMOVE | ALLOW | 别理楼上那个智障，天天在这发疯。 | No configured term matched. This engine cannot detect wording it has not been given. |
| s117 | SPAM | REMOVE | ESCALATE | Click here for free money, limited offer for students only. | Matched a low-severity term from SPAM. |
| s118 | SPAM | REMOVE | ESCALATE | Buy now and get 50% off our exam prep bundle, DM for the link. | Matched a low-severity term from SPAM. |
| s119 | SPAM | REMOVE | ALLOW | Professional academic support service. Fast turnaround, competitive rates, message me. | No configured term matched. This engine cannot detect wording it has not been given. |

_70 more in `evaluation-samples.csv`._

## `gemini-v1` in detail

Answered nothing. No engine named 'gemini-v1'. Available: [keyword-v1]

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
