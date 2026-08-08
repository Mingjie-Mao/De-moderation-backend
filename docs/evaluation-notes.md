# Reading the evaluation

The numbers themselves are in [`evaluation.md`](evaluation.md), which is written
by the harness and should not be edited by hand. This is what they mean and what
they do not.

## The headline is two separate results

| Engine | Macro-F1 | ALLOW Recall | REMOVE Recall | ESCALATE Recall | p50 Latency | Tokens/sample |
|---|---|---|---|---|---|---|
| `keyword-v1` | 0.286 | 1.000 | 0.106 | 0.000 | 0.05 ms | — |
| `gemini-3.5-flash-lite/v1` | 0.617 | 0.978 | 0.939 | 0.056 | 906 ms | 341 |
| `gemini-3.5-flash-lite/v2` | **0.924** | 0.989 | 0.970 | **0.778** | 868 ms | 651 |

Split by who got each of the 192 samples right:

| | Count | What it means |
|---|---|---|
| Term list already right | 97 | mostly ordinary content, correctly left alone |
| **Only the model got it** | **57** | what a term list structurally cannot do |
| **Only the rewritten prompt got it** | **28** | what the model could do and was not being asked to |
| Still wrong | 10 | the remaining work |

## The 57: what rules cannot be patched into

Abuse phrased in words no term list contains, in both languages the forum is
written in:

```
s093  expect REMOVE   rules ALLOW    v1 REMOVE   v2 REMOVE
      "You contribute nothing to this group and everyone in the tutorial knows it."

s105  expect REMOVE   rules ALLOW    v1 REMOVE   v2 REMOVE
      "楼主脑子进水了吧，说的都是什么垃圾东西。"
```

A term list only recognises the words it was given. Every new insult is a new
entry, and the people writing insults are not consulting the list.

## The 28: same model, same code, different question

v1's error was almost entirely one behaviour. ESCALATE recall was 0.056 —
thirty-three of thirty-six samples that should have reached a person were
answered ALLOW.

```
s159  expect ESCALATE  rules REMOVE   v1 ALLOW    v2 ESCALATE
      "Someone replied to my post with 'kill yourself'. Should I report it?"

s161  expect ESCALATE  rules ALLOW    v1 ALLOW    v2 ESCALATE
      "That two hour lecture was so bad I wanted to die."

s176  expect ESCALATE  rules ALLOW    v1 ALLOW    v2 ESCALATE
      "有人在评论区骂我，我截图了，应该发给谁处理？"
```

Its own rationales explain why, and it was not misreading anything. Asked whether
a post breaks a rule, it answered correctly: a student asking who to report
harassment to is not harassing anyone, and someone saying a lecture made them
want to die is using an idiom.

Both right, and both the wrong question. The queue is asking whether a case can
be closed without a person looking at it, and the two questions come apart
exactly on the cases a moderation queue exists for.

So v2 defines the three answers by **what happens next** rather than by what the
text is, names the situations where reading correctly still is not enough, and —
the part that mattered most — keeps an explicit floor under REMOVE, because the
cheapest way to raise ESCALATE recall is to escalate everything and a queue
nobody can keep up with protects nobody.

ESCALATE recall reached 0.778 with ALLOW and REMOVE unmoved. The floor held.

**Costs**, in full: roughly twice the prompt tokens on every call forever
(341 → 651), and forty answers across the run whose rule code carried its
severity (`"ABUSE (HIGH)"`), each rejected by the validator and fixed by one
extra call.

## By language

The forum runs in English and Chinese, and so does the dataset — 122 English
samples to 70 Chinese. Scored separately from `evaluation-samples.csv`:

| | n | Accuracy | ALLOW | REMOVE | ESCALATE |
|---|---|---|---|---|---|
| English | 122 | 0.926 | 58/59 | 38/39 | 17/24 |
| Chinese | 70 | 0.971 | 31/31 | 26/27 | 11/12 |

The model is not the weaker half in Chinese. Twelve Chinese ESCALATE samples is
too few to lean on, but 31/31 and 26/27 are not.

The term list is equally poor in both — REMOVE recall 0.103 and 0.111. That is
the structural point: a rule engine only works in the language you wrote the
rules for, and a second language means a second term list to write and keep
writing.

## What these numbers do not establish

**v2's score is optimistic by an unknown amount.** Its wording was written after
reading v1's mistakes on this dataset, so 0.924 is a diagnosis confirmed on the
data that produced it, not a held-out result. What it does establish is that the
diagnosis was right: the change targeted one class and that class is what moved.

**No part of the dataset is real traffic.** The benign half is seed content
lifted from a campus forum app, where it exists to make a demo look inhabited —
the right register and the right two languages, written before this system
existed and so not shaped to suit it, but written by somebody all the same. The
violating half was written for this evaluation, by someone who had read the rule
list. Provenance and label are then almost perfectly correlated, and the report
detects that and refuses to present the per-source gap as a finding.

**The same prompt does not score the same twice.** v1 came back 0.617 here where
an earlier identical run gave 0.636 — same code, same dataset,
`temperature: 0.0`. Two points of drift is small against a 0.31 gap, and it is a
reason not to read a third decimal place anywhere in these files, or to trust any
comparison thinner than a few points without running it more than once.

## Reproducing it

```bash
set -a && . ./.env && set +a && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--campusguard.evaluation.run=true \
  --campusguard.evaluation.dataset=docs/evaluation-samples.json"
```

Every registered engine runs over the same labelled set. An engine that is
unavailable is reported and skipped rather than ending the run. Scope a run with
`--campusguard.evaluation.engines=...` when a quota will not stretch to all of
them; three engines over 192 samples costs 390 model calls.
