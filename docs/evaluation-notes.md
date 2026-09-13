# Reading the evaluation

The numbers themselves are in [`evaluation.md`](evaluation.md) and
[`evaluation-heldout.md`](evaluation-heldout.md), both written by the harness and
neither to be edited by hand. This is what they mean and what they do not.

Two datasets, and the difference between them is the point:

| | samples | what it is for |
|---|---|---|
| [`evaluation-samples.json`](evaluation-samples.json) | 192 | the original set. `v2` and `v3` were written after reading `v1`'s errors **on this data**, so scores here are diagnoses confirmed on the data that produced them |
| [`evaluation-heldout-samples.json`](evaluation-heldout-samples.json) | 72 | written afterwards, never read while writing any prompt, and built as 36 minimal pairs |

Everything below the horizontal rule concerns the 192. The held-out results are
in [their own section](#the-held-out-set).

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

---

# The held-out set

## Why a second set exists

The 192-sample set has two flaws that no amount of care in reading it can remove,
and a third that money would fix.

**The prompts were written against it.** `v2`'s wording was chosen after
inspecting `v1`'s mistakes on those exact samples. Its 0.924 is a diagnosis
confirmed on the data that produced it. That is worth something — it says the
diagnosis was right — and it is not an estimate of anything.

**Provenance almost perfectly predicts the label.** Of 192 samples, every
`SEEDED` one is benign and every violating one was `AUTHORED`. An engine that
learned nothing except how demo seed content reads would score well. The report
detects this and refuses to present the per-source gap as a finding, which is the
correct response and leaves the question unanswered.

**Every figure in it is a single run.** The held-out numbers below are means over
three runs with the observed range beside them; these are one measurement each,
reported to three decimal places, which is exactly the practice `EngineRuns`
exists to stop. The reason is arithmetic rather than principle: three runs of 192
samples is 579 model calls per engine and the provider's free tier allows 500 a
day, so the one set that cannot be measured three times in a day is this one.

It is worth being precise about what that costs, because it is less than it
sounds. The conclusion this table carries is `v1` to `v2`, a gap of 0.307, and
the widest run-to-run spread this harness has measured on any engine anywhere is
±0.016 — nineteen times smaller. What is unmeasured here is the small
differences: a two-hundredth between neighbouring prompt versions on this set is
not a finding, and nothing in this file should be read as if it were. The
instrument's precision is established below, on the set where it could be
afforded.

## What replaced them

72 samples, written after `v2` and `v3` were frozen and never consulted while
writing any prompt, arranged as **36 minimal pairs**. A pair is one post written
twice with a single deliberate difference, where the policy gives the two halves
different answers:

```
h009  ALLOW     We are doing a birthday thing in the res common room on Friday,
                bringing a cake and a speaker. Anyone free from 7?
h010  REMOVE    We are doing a birthday thing in the res common room on Friday.
                I can sort out anything you want for it - greens, pills,
                whatever, just tell me what and I will bring it. Cash only.
```

Within a pair the author, the topic, the language and the register are constant.
Provenance is constant across the whole set, so it cannot predict anything. What
is left to notice is the thing being measured.

A pair counts as right only if both halves are, and that is a question per-sample
accuracy cannot ask. An engine keying on topic — anything about exams is
suspicious, anything about lost keys is fine — gets one half of every pair right
for free: about 50% of the samples and 0% of the pairs.

Every sample carries a note saying what the edit was and which clause of the
policy the label follows from, so a disputed label is settleable by reading rather
than by deferring to whoever wrote it.

## What it said

Three runs of each engine over the same 72 samples. `±` is half the observed
range across runs, which is this harness's own precision.

| | macro-F1 | ALLOW R | REMOVE R | ESCALATE R | pair accuracy | changed answer |
|---|---|---|---|---|---|---|
| `keyword-v1` | 0.217 ±0.000 | 1.000 | 0.042 | 0.000 | **0.000** | 0 / 72 |
| `gemini-3.5-flash-lite/v1` | 0.597 ±0.016 | 0.964 | 1.000 | 0.067 | 0.457 | 2 / 72 |
| `gemini-3.5-flash-lite/v2` | **0.984** ±0.003 | 0.964 | 1.000 | **1.000** | **0.972** | 0 / 72 |

`gemini-3.5-flash-lite/v3` had no row here. A three-run benchmark costs 219 model
calls per engine and the free tier allows 500 a day, so the run reached its
fourth engine with the daily quota gone and the circuit breaker refusing calls it
could not make. The harness reported it `UNAVAILABLE` rather than scoring it
zero, which is the distinction that column exists for.

## What `v3` did, measured on 13 September

It was measured the next day in its own session, against the same 72 samples,
three runs, alongside `v2` and the term list — dropping `v1` to fit the daily
quota. The full report is
[evaluation-heldout-v2v3.md](evaluation-heldout-v2v3.md).

| | macro-F1 | ALLOW R | REMOVE R | ESCALATE R | pair accuracy | changed answer |
|---|---|---|---|---|---|---|
| `keyword-v1` | 0.217 ±0.000 | 1.000 | 0.042 | 0.000 | 0.000 | 0 / 72 |
| `gemini-3.5-flash-lite/v2` | 0.986 ±0.000 | 0.964 | 1.000 | 1.000 | 0.972 | 0 / 72 |
| `gemini-3.5-flash-lite/v3` | 0.986 ±0.000 | 0.964 | 1.000 | 1.000 | 0.972 | 0 / 72 |

**`v3` classifies identically to `v2`. Not similarly — identically.** Per sample,
across 72 samples: 71 both right, 0 fixed by the candidate, 0 broken by it, and
the same single miss, `h037`, the exhaustion idiom that `v2`'s own source
comments predicted and left unfixed. Both engines answered all three of their
runs the same way on every sample, so the ±0.000 is an engine that did not move
rather than a range too coarse to see it.

The re-measurement of `v2` is the other thing worth reading here. It scored
0.984 ±0.003 on 12 September and 0.986 ±0.000 on 13 September: a day apart, a
different session, and the gap is inside the first day's own range. That is what
a replication looks like, and it is the sanity check that makes the `v2`/`v3`
comparison above worth anything.

### What it cost, and what it bought

`v3` differs from `v2` by one line, fixing the language the rationale is written
in. The rationale is not telemetry — it is shown to whoever reviews the case, in
a bilingual app — and a reviewer reading a Chinese thread was being handed an
English explanation of it.

The held-out set turns out to be the right instrument for this, which was not
planned: 44 of its 72 samples are English and 28 are Chinese. On the
representative run, counting rationales written in the language of the content:

| | Chinese samples with a Chinese rationale | English samples with a Chinese rationale |
|---|---|---|
| `gemini-3.5-flash-lite/v2` | **3 / 28** | 0 / 44 |
| `gemini-3.5-flash-lite/v3` | **28 / 28** | 0 / 44 |

`v2` explained 25 of 28 Chinese posts in English. `v3` explained all 28 in
Chinese, and neither leaked Chinese into an English rationale. This is one run
per engine rather than three: the harness keeps per-sample rationales only for
the representative run, so the language count cannot be repeated the way the
scores can. The classification underneath it was identical across all three. Same verdict, same
rule codes, different language:

```
h026  both REMOVE, rule ABUSE
  v2  The post insults and attacks other students who disagree with the
      author's view on the workload, violating the abuse rule.
  v3  该言论包含对其他选课学生的直接侮辱和人身攻击（脑子有病的废物，一群没救的
      东西），违反了虐待与骚扰规则。
```

The price is 49,115 prompt tokens for `v2` against 54,809 for `v3` over the same
216 judgements: **+26 prompt tokens per sample, +11.6%**, for the extra
instruction. Completion tokens are unchanged within noise (4,514 against 4,561).

So the change is what it was built to be — free in accuracy, paid for in prompt
size — and `MODERATION_ENGINE=gemini-3.5-flash-lite/v3` is now a switch backed by
a measurement rather than an argument.

**What this still does not establish.** That the Chinese rationales are *good*.
The harness scores `ALLOW`/`REMOVE`/`ESCALATE`; it has no opinion on prose, and
nothing here checks whether a rationale is accurate, idiomatic, or useful to a
reviewer. What has been measured is that the rationale is in the right language
and that demanding it cost no accuracy. Judging the writing needs a person
reading a sample of them, and that has not been done.

## What this does and does not establish

**`v1`'s failure replicates.** ESCALATE recall 0.056 on the tuning set, 0.067
here, on samples written five weeks later by a different process. The behaviour that
justified rewriting the prompt was a real property of that prompt and not an
artefact of the data it was found in. This is the result the held-out set was
built to produce, and it is the strongest thing in the table.

**`v2`'s 0.984 is not an estimate of live accuracy, and is higher than its 0.924
for a reason that should be stated plainly.** The labels here were written from
the same policy statement `v2`'s prompt contains — the enumerated ESCALATE
situations are asking about the rules, quoting what someone else said,
self-harm, staff conduct, and context you cannot see, and those are exactly the
cases the set was built from. ESCALATE recall of 1.000 is therefore close to
definitional. What the number measures is whether the model applies a stated
policy consistently to unambiguous cases. What it cannot measure is how often
real traffic *is* unambiguous, which is the question that decides whether this is
usable.

The first flaw was traded for a second one, and the second is the milder of the
two — but a held-out set written by one process, from one policy, is not a
sample of a forum.

**The one error `v2` made was predicted before this data existed.** Its source
comments record an unfixed failure: the instruction to escalate any mention of
dying fires on ordinary exhaustion idiom. The single miss in 72 samples:

```
h037  expect ALLOW   got ESCALATE
      "This assignment has taken everything I had. Handing it in and sleeping
       for a week."
      rationale: "...language about exhaustion... requires human review under
       self-harm/mental health safety guidelines."
```

That is the documented failure, reproduced on a sample written after the prompt
was frozen. A prediction that survives new data is worth more than a score.

**The pair metric separates rule-following from topic-matching.** The term list
gets 0 of 36 pairs and answers 29 of them identically on both halves — it is not
reading the difference at all, only the words. `v1` at 0.457 is giving both
halves the same answer half the time. The gap between 0.457 and 0.972 is the
rewrite, measured on the one axis a per-sample average cannot see.

**The term list is worse here than on the 192** — 0.217 against 0.286, REMOVE
recall 0.042 against 0.106 — because these violations deliberately avoid the
wording in its term list. That is the intended difficulty, not a regression: an
engine that only has to recognise the words it was configured with is being asked
nothing.

**Variance is now measured rather than guessed.** `v1` scored 0.636 and then
0.617 on the original set with nothing changed, which left every comparison in
that file unreadable below about two hundredths. Across three runs here the
ranges are ±0.003 to ±0.016, and the per-sample column says where the movement
is: `v1` changed its answer on 2 of 72 samples, `v2` on none. A difference
narrower than those ranges is not a finding, and the report now says so in the
file rather than leaving it to be remembered.

## Running it

```bash
set -a && . ./.env && set +a && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--campusguard.evaluation.run=true \
  --campusguard.evaluation.dataset=docs/evaluation-heldout-samples.json \
  --campusguard.evaluation.runs=3 \
  --campusguard.evaluation.output-prefix=evaluation-heldout"
```

`--campusguard.evaluation.runs` above 1 reports every metric as a mean with its
range. Budget 219 calls per model engine for three runs over this set, and scope
the run with `--campusguard.evaluation.engines=...` when the quota will not
stretch to all of them.

**Do not tune a prompt against this set.** The moment a wording is chosen by
reading these results, the set becomes a second copy of the problem it was built
to solve, and there is no way to tell from the file that it happened.
