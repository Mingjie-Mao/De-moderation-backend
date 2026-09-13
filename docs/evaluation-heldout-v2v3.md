# Moderation engine benchmark

_Generated 2026-09-13 by `EvaluationCommand`. Do not edit by hand._

Dataset `evaluation-heldout-samples.json`, 72 samples.

## What this dataset is

| expected action | seeded | written for this | total |
|---|---|---|---|
| ALLOW | 0 | 28 | 28 |
| REMOVE | 0 | 24 | 24 |
| ESCALATE | 0 | 20 | 20 |

Every sample here was written for this evaluation. That removes the flaw the
192-sample set has and cannot lose — there, the source of a sample almost
perfectly predicts its label, so part of any good score is a reward for
noticing which half a sample came from — and it removes the per-source
comparison along with it. There is one source, so that breakdown says
nothing and is not drawn.

What replaces it is the pair structure. 72 of these samples are one half of a
minimal pair: the same post, one deliberate difference, two different
correct answers. Within a pair the author, the topic, the language and the
register are held constant, so the only thing left to notice is the thing
being measured. Pairs are scored as units further down.

It is still not real traffic, and it never becomes real traffic by being
harder.

## Side by side

| engine | status | accuracy | macro-F1 | macro-P | macro-R | errors | mean | p50 | p95 | tokens | est. cost |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `keyword-v1` | OK | 0.403 | 0.217 | 0.246 | 0.347 | 0 | 0.121 ms | 0.121 ms | 0.184 ms | 0 | $0 |
| `gemini-3.5-flash-lite/v2` | OK | 0.986 | 0.986 | 0.984 | 0.988 | 0 | 1202.757 ms | 1107.239 ms | 2095.616 ms | 49115 + 4514 | not priced |
| `gemini-3.5-flash-lite/v3` | OK | 0.986 | 0.986 | 0.984 | 0.988 | 0 | 1141.209 ms | 1091.989 ms | 1416.964 ms | 54809 + 4561 | not priced |

An engine that answered nothing scores zero on every column, which on a table
is indistinguishable from an engine that answered everything wrongly. The
status column is there so those two are never confused.

## How much of this is noise

Every engine measured 3 times on the same samples. `mean` is across runs and
`range` is the widest observed minus the narrowest, which is this harness's
own precision: **a gap between two engines narrower than their ranges is not
a finding.**

| engine | runs | macro-F1 mean | range | ALLOW R | REMOVE R | ESCALATE R | changed answer |
|---|---|---|---|---|---|---|---|
| `keyword-v1` | 3 | 0.217 | ±0.000 | 1.000 ±0.000 | 0.042 ±0.000 | 0.000 ±0.000 | 0 / 72 |
| `gemini-3.5-flash-lite/v2` | 3 | 0.986 | ±0.000 | 0.964 ±0.000 | 1.000 ±0.000 | 1.000 ±0.000 | 0 / 72 |
| `gemini-3.5-flash-lite/v3` | 3 | 0.986 | ±0.000 | 0.964 ±0.000 | 1.000 ±0.000 | 1.000 ±0.000 | 0 / 72 |

`changed answer` counts samples the engine did not answer identically every
time. It is the same instability the range describes, at the granularity
where it can be acted on: a score that holds steady because the same
handful of samples flip in opposite directions is not the same thing as an
engine that is steady, and only this column separates them.

The representative run below is the median by macro-F1, not the best and not
the first. Every per-sample table, confusion matrix and comparison comes
from that one run, so they agree with each other; an average of three
matrices would correspond to no run that happened and could not be traced
to a row in the CSV.

## Can it tell two near-identical posts apart

A minimal pair is one post written twice with a single deliberate difference,
where the policy gives the two halves different answers. A pair counts as
right only if both halves are.

This is the question per-sample accuracy cannot ask. An engine that answers by
topic — anything about exams is suspicious, anything about lost keys is fine
— gets one half of every pair right for free. That reads as roughly 50%
accuracy on the samples and 0% on the pairs, and the second number is the
one that says whether it understood the rule.

| engine | pairs | both right | one right | both wrong | pair accuracy |
|---|---|---|---|---|---|
| `keyword-v1` | 36 | 0 | 29 | 7 | 0.000 |
| `gemini-3.5-flash-lite/v2` | 36 | 35 | 1 | 0 | 0.972 |
| `gemini-3.5-flash-lite/v3` | 36 | 35 | 1 | 0 | 0.972 |

`one right` is the diagnostic column: it is the engine giving both halves the
same answer, which is what keying on topic looks like from outside.

## Where they disagree

A higher average is not by itself a reason to switch. What matters is whether
the candidate fixed the baseline's mistakes or traded them for different
ones, and only the per-sample comparison answers that.

### `gemini-3.5-flash-lite/v2` against `keyword-v1`

| | count |
|---|---|
| both right | 28 |
| candidate fixed | 43 |
| candidate broke | 1 |
| both wrong | 0 |
| **net** | **+42** |

**Fixed by the candidate** (43)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| h002 | SPAM | REMOVE | ALLOW | REMOVE | Spare tickets for the engineering ball this Saturday, I can get as many as you want at ... |
| h004 | ILLEGAL | REMOVE | ALLOW | REMOVE | I have a guy who will do question 3 and the whole assignment for you, turnaround under ... |
| h006 | ABUSE | REMOVE | ALLOW | REMOVE | You have misread the rubric because you are too thick to read it, and honestly people l... |
| h008 | SPAM | REMOVE | ALLOW | REMOVE | Selling second-hand textbooks for every first-year course, bulk stock, new arrivals wee... |
| h010 | ILLEGAL | REMOVE | ALLOW | REMOVE | We are doing a birthday thing in the res common room on Friday. I can sort out anything... |
| h012 | ABUSE | REMOVE | ALLOW | REMOVE | We lost the intercollege final. Honestly anyone who plays for the other college is a br... |
| h014 | SPAM | REMOVE | ALLOW | REMOVE | STAT1008 exam prep, $40/hr, first session free, limited spots this week only - message ... |
| h016 | ILLEGAL | REMOVE | ALLOW | REMOVE | If anyone needs an ID that scans as over 18, I know someone who makes them, photo and a... |
| h018 | ABUSE | REMOVE | ALLOW | REMOVE | My housemate keeps leaving dishes for a week. Anyone from that country is a filthy anim... |
| h020 | SPAM | REMOVE | ALLOW | REMOVE | Crypto meetup Thursday 7pm - learn how students are making $2k a week from home. Free e... |
| h022 | SPAM | REMOVE | ALLOW | REMOVE | 长期收售各种电子产品，显示器键盘耳机都有，每天更新，加我私信拿最新报价，介绍朋友来还能再减五十。 |
| h024 | ILLEGAL | REMOVE | ALLOW | REMOVE | 这门课的 essay 不用自己写，我这边有稳定的人接，按字数算钱，48 小时出稿，保过，往年很多人找我。私聊报课号。 |
| h026 | ABUSE | REMOVE | ALLOW | REMOVE | 我觉得这门课的作业量不合理，选这门课还觉得没问题的都是脑子有病的废物，一群没救的东西，看到就烦。 |
| h028 | SPAM | REMOVE | ALLOW | REMOVE | 有个活儿，在家就能做，一天下来两三百不成问题，不看经验，名额不多，想做的私信我发详情，带人来还有额外的。 |
| h030 | ILLEGAL | REMOVE | ALLOW | REMOVE | 往年题的完整答案我这儿都有，包括这学期的期中，一份五十，要的私信，别在楼下问。 |
| h032 | ABUSE | REMOVE | ALLOW | REMOVE | 昨天那场输得冤，对面那群人就是一堆垃圾，全都该死，长这样也敢上场，真的看了想吐。 |
| h034 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | I took a photo of our tutorial whiteboard and someone else's working was in the corner.... |
| h036 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Someone in my tute messaged me after class saying I should kill myself and that he know... |
| h038 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | This assignment has taken everything I had. Some days I genuinely do not want to be her... |
| h040 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | The feedback on my essay was two words, and when I asked about it in the corridor the t... |
| h042 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Someone put a photo of me from the college dinner in the shared album and I had asked t... |
| h044 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Reminder that the essay-writing service ads going round the first-year chats are a scam... |
| h046 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Someone has been leaving notes on my bike outside Baldessin telling me to watch myself.... |
| h048 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | 我们整理的共享笔记里有一部分是直接抄的老师讲义原文，这样发出来算违规吗？我不太确定能不能放。 |
| h050 | BORDERLINE | ESCALATE | REMOVE | ESCALATE | 组里有个人昨天私信我，说我这种人就该去死，还说知道我住哪一栋。我不知道该怎么处理，先截图了。 |

_18 more in the per-sample CSV beside this file._

**Broken by the candidate** (1)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| h037 | NORMAL | ALLOW | ALLOW | ESCALATE | This assignment has taken everything I had. Handing it in and sleeping for a week. |

**Wrong in both** (0)

None.

### `gemini-3.5-flash-lite/v3` against `keyword-v1`

| | count |
|---|---|
| both right | 28 |
| candidate fixed | 43 |
| candidate broke | 1 |
| both wrong | 0 |
| **net** | **+42** |

**Fixed by the candidate** (43)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| h002 | SPAM | REMOVE | ALLOW | REMOVE | Spare tickets for the engineering ball this Saturday, I can get as many as you want at ... |
| h004 | ILLEGAL | REMOVE | ALLOW | REMOVE | I have a guy who will do question 3 and the whole assignment for you, turnaround under ... |
| h006 | ABUSE | REMOVE | ALLOW | REMOVE | You have misread the rubric because you are too thick to read it, and honestly people l... |
| h008 | SPAM | REMOVE | ALLOW | REMOVE | Selling second-hand textbooks for every first-year course, bulk stock, new arrivals wee... |
| h010 | ILLEGAL | REMOVE | ALLOW | REMOVE | We are doing a birthday thing in the res common room on Friday. I can sort out anything... |
| h012 | ABUSE | REMOVE | ALLOW | REMOVE | We lost the intercollege final. Honestly anyone who plays for the other college is a br... |
| h014 | SPAM | REMOVE | ALLOW | REMOVE | STAT1008 exam prep, $40/hr, first session free, limited spots this week only - message ... |
| h016 | ILLEGAL | REMOVE | ALLOW | REMOVE | If anyone needs an ID that scans as over 18, I know someone who makes them, photo and a... |
| h018 | ABUSE | REMOVE | ALLOW | REMOVE | My housemate keeps leaving dishes for a week. Anyone from that country is a filthy anim... |
| h020 | SPAM | REMOVE | ALLOW | REMOVE | Crypto meetup Thursday 7pm - learn how students are making $2k a week from home. Free e... |
| h022 | SPAM | REMOVE | ALLOW | REMOVE | 长期收售各种电子产品，显示器键盘耳机都有，每天更新，加我私信拿最新报价，介绍朋友来还能再减五十。 |
| h024 | ILLEGAL | REMOVE | ALLOW | REMOVE | 这门课的 essay 不用自己写，我这边有稳定的人接，按字数算钱，48 小时出稿，保过，往年很多人找我。私聊报课号。 |
| h026 | ABUSE | REMOVE | ALLOW | REMOVE | 我觉得这门课的作业量不合理，选这门课还觉得没问题的都是脑子有病的废物，一群没救的东西，看到就烦。 |
| h028 | SPAM | REMOVE | ALLOW | REMOVE | 有个活儿，在家就能做，一天下来两三百不成问题，不看经验，名额不多，想做的私信我发详情，带人来还有额外的。 |
| h030 | ILLEGAL | REMOVE | ALLOW | REMOVE | 往年题的完整答案我这儿都有，包括这学期的期中，一份五十，要的私信，别在楼下问。 |
| h032 | ABUSE | REMOVE | ALLOW | REMOVE | 昨天那场输得冤，对面那群人就是一堆垃圾，全都该死，长这样也敢上场，真的看了想吐。 |
| h034 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | I took a photo of our tutorial whiteboard and someone else's working was in the corner.... |
| h036 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Someone in my tute messaged me after class saying I should kill myself and that he know... |
| h038 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | This assignment has taken everything I had. Some days I genuinely do not want to be her... |
| h040 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | The feedback on my essay was two words, and when I asked about it in the corridor the t... |
| h042 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Someone put a photo of me from the college dinner in the shared album and I had asked t... |
| h044 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Reminder that the essay-writing service ads going round the first-year chats are a scam... |
| h046 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Someone has been leaving notes on my bike outside Baldessin telling me to watch myself.... |
| h048 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | 我们整理的共享笔记里有一部分是直接抄的老师讲义原文，这样发出来算违规吗？我不太确定能不能放。 |
| h050 | BORDERLINE | ESCALATE | REMOVE | ESCALATE | 组里有个人昨天私信我，说我这种人就该去死，还说知道我住哪一栋。我不知道该怎么处理，先截图了。 |

_18 more in the per-sample CSV beside this file._

**Broken by the candidate** (1)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| h037 | NORMAL | ALLOW | ALLOW | ESCALATE | This assignment has taken everything I had. Handing it in and sleeping for a week. |

**Wrong in both** (0)

None.

### `gemini-3.5-flash-lite/v3` against `gemini-3.5-flash-lite/v2`

| | count |
|---|---|
| both right | 71 |
| candidate fixed | 0 |
| candidate broke | 0 |
| both wrong | 1 |
| **net** | **+0** |

**Fixed by the candidate** (0)

None.

**Broken by the candidate** (0)

None.

**Wrong in both** (1)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| h037 | NORMAL | ALLOW | ESCALATE | ESCALATE | This assignment has taken everything I had. Handing it in and sleeping for a week. |

## `keyword-v1` in detail

| decision | support | precision | recall | F1 |
|---|---|---|---|---|
| ALLOW | 28 | 0.406 | 1.000 | 0.577 |
| REMOVE | 24 | 0.333 | 0.042 | 0.074 |
| ESCALATE | 20 | 0.000 | 0.000 | 0.000 |

Rows are the labelled answer, columns what the engine said.

| actual \ predicted | ALLOW | REMOVE | ESCALATE |
|---|---|---|---|
| **ALLOW** | 28 | 0 | 0 |
| **REMOVE** | 23 | 1 | 0 |
| **ESCALATE** | 18 | 2 | 0 |

43 of 72 answered samples were judged wrongly.

| sample | category | expected | got | content | engine said |
|---|---|---|---|---|---|
| h002 | SPAM | REMOVE | ALLOW | Spare tickets for the engineering ball this Saturday, I can get as many as you want at ... | No configured term matched. This engine cannot detect wording it has not been given. |
| h004 | ILLEGAL | REMOVE | ALLOW | I have a guy who will do question 3 and the whole assignment for you, turnaround under ... | No configured term matched. This engine cannot detect wording it has not been given. |
| h006 | ABUSE | REMOVE | ALLOW | You have misread the rubric because you are too thick to read it, and honestly people l... | No configured term matched. This engine cannot detect wording it has not been given. |
| h008 | SPAM | REMOVE | ALLOW | Selling second-hand textbooks for every first-year course, bulk stock, new arrivals wee... | No configured term matched. This engine cannot detect wording it has not been given. |
| h010 | ILLEGAL | REMOVE | ALLOW | We are doing a birthday thing in the res common room on Friday. I can sort out anything... | No configured term matched. This engine cannot detect wording it has not been given. |
| h012 | ABUSE | REMOVE | ALLOW | We lost the intercollege final. Honestly anyone who plays for the other college is a br... | No configured term matched. This engine cannot detect wording it has not been given. |
| h014 | SPAM | REMOVE | ALLOW | STAT1008 exam prep, $40/hr, first session free, limited spots this week only - message ... | No configured term matched. This engine cannot detect wording it has not been given. |
| h016 | ILLEGAL | REMOVE | ALLOW | If anyone needs an ID that scans as over 18, I know someone who makes them, photo and a... | No configured term matched. This engine cannot detect wording it has not been given. |
| h018 | ABUSE | REMOVE | ALLOW | My housemate keeps leaving dishes for a week. Anyone from that country is a filthy anim... | No configured term matched. This engine cannot detect wording it has not been given. |
| h020 | SPAM | REMOVE | ALLOW | Crypto meetup Thursday 7pm - learn how students are making $2k a week from home. Free e... | No configured term matched. This engine cannot detect wording it has not been given. |
| h022 | SPAM | REMOVE | ALLOW | 长期收售各种电子产品，显示器键盘耳机都有，每天更新，加我私信拿最新报价，介绍朋友来还能再减五十。 | No configured term matched. This engine cannot detect wording it has not been given. |
| h024 | ILLEGAL | REMOVE | ALLOW | 这门课的 essay 不用自己写，我这边有稳定的人接，按字数算钱，48 小时出稿，保过，往年很多人找我。私聊报课号。 | No configured term matched. This engine cannot detect wording it has not been given. |
| h026 | ABUSE | REMOVE | ALLOW | 我觉得这门课的作业量不合理，选这门课还觉得没问题的都是脑子有病的废物，一群没救的东西，看到就烦。 | No configured term matched. This engine cannot detect wording it has not been given. |
| h028 | SPAM | REMOVE | ALLOW | 有个活儿，在家就能做，一天下来两三百不成问题，不看经验，名额不多，想做的私信我发详情，带人来还有额外的。 | No configured term matched. This engine cannot detect wording it has not been given. |
| h030 | ILLEGAL | REMOVE | ALLOW | 往年题的完整答案我这儿都有，包括这学期的期中，一份五十，要的私信，别在楼下问。 | No configured term matched. This engine cannot detect wording it has not been given. |
| h032 | ABUSE | REMOVE | ALLOW | 昨天那场输得冤，对面那群人就是一堆垃圾，全都该死，长这样也敢上场，真的看了想吐。 | No configured term matched. This engine cannot detect wording it has not been given. |
| h034 | BORDERLINE | ESCALATE | ALLOW | I took a photo of our tutorial whiteboard and someone else's working was in the corner.... | No configured term matched. This engine cannot detect wording it has not been given. |
| h036 | BORDERLINE | ESCALATE | ALLOW | Someone in my tute messaged me after class saying I should kill myself and that he know... | No configured term matched. This engine cannot detect wording it has not been given. |
| h038 | BORDERLINE | ESCALATE | ALLOW | This assignment has taken everything I had. Some days I genuinely do not want to be her... | No configured term matched. This engine cannot detect wording it has not been given. |
| h040 | BORDERLINE | ESCALATE | ALLOW | The feedback on my essay was two words, and when I asked about it in the corridor the t... | No configured term matched. This engine cannot detect wording it has not been given. |
| h042 | BORDERLINE | ESCALATE | ALLOW | Someone put a photo of me from the college dinner in the shared album and I had asked t... | No configured term matched. This engine cannot detect wording it has not been given. |
| h044 | BORDERLINE | ESCALATE | ALLOW | Reminder that the essay-writing service ads going round the first-year chats are a scam... | No configured term matched. This engine cannot detect wording it has not been given. |
| h046 | BORDERLINE | ESCALATE | ALLOW | Someone has been leaving notes on my bike outside Baldessin telling me to watch myself.... | No configured term matched. This engine cannot detect wording it has not been given. |
| h048 | BORDERLINE | ESCALATE | ALLOW | 我们整理的共享笔记里有一部分是直接抄的老师讲义原文，这样发出来算违规吗？我不太确定能不能放。 | No configured term matched. This engine cannot detect wording it has not been given. |
| h050 | BORDERLINE | ESCALATE | REMOVE | 组里有个人昨天私信我，说我这种人就该去死，还说知道我住哪一栋。我不知道该怎么处理，先截图了。 | Matched a high-severity term from ABUSE. |

_18 more in the per-sample CSV beside this file._

## `gemini-3.5-flash-lite/v2` in detail

| decision | support | precision | recall | F1 |
|---|---|---|---|---|
| ALLOW | 28 | 1.000 | 0.964 | 0.982 |
| REMOVE | 24 | 1.000 | 1.000 | 1.000 |
| ESCALATE | 20 | 0.952 | 1.000 | 0.976 |

Rows are the labelled answer, columns what the engine said.

| actual \ predicted | ALLOW | REMOVE | ESCALATE |
|---|---|---|---|
| **ALLOW** | 27 | 0 | 1 |
| **REMOVE** | 0 | 24 | 0 |
| **ESCALATE** | 0 | 0 | 20 |

1 of 72 answered samples were judged wrongly.

| sample | category | expected | got | content | engine said |
|---|---|---|---|---|---|
| h037 | NORMAL | ALLOW | ESCALATE | This assignment has taken everything I had. Handing it in and sleeping for a week. | Contains language about exhaustion and 'taking everything I had' which requires manual review to ensure student well-being. |

## `gemini-3.5-flash-lite/v3` in detail

| decision | support | precision | recall | F1 |
|---|---|---|---|---|
| ALLOW | 28 | 1.000 | 0.964 | 0.982 |
| REMOVE | 24 | 1.000 | 1.000 | 1.000 |
| ESCALATE | 20 | 0.952 | 1.000 | 0.976 |

Rows are the labelled answer, columns what the engine said.

| actual \ predicted | ALLOW | REMOVE | ESCALATE |
|---|---|---|---|
| **ALLOW** | 27 | 0 | 1 |
| **REMOVE** | 0 | 24 | 0 |
| **ESCALATE** | 0 | 0 | 20 |

1 of 72 answered samples were judged wrongly.

| sample | category | expected | got | content | engine said |
|---|---|---|---|---|---|
| h037 | NORMAL | ALLOW | ESCALATE | This assignment has taken everything I had. Handing it in and sleeping for a week. | Mentions feeling completely drained by an assignment and sleeping for a week, which requires escalation due to self-harm and well-being safety guidelines. |

## On real traffic

From `ai_invocations`: what these engines have actually done, as opposed to how
they score on a set someone chose. An engine can look good here and be
unusable there.

| engine | calls | succeeded | failed | mean | p95 | prompt tokens | completion tokens |
|---|---|---|---|---|---|---|---|
| `gemini-3.5-flash-lite/v2` | 9 | 8 | 1 | 1459.333 ms | 3333.000 ms | 5598 | 516 |
| `gemini-v1` | 1 | 1 | 0 | 3384.000 ms | 3384.000 ms | 285 | 39 |
| `investigator/inv-v4` | 3 | 3 | 0 | 3586.333 ms | 5977.000 ms | 6803 | 769 |

## Reproducing this

```bash
set -a && . ./.env && set +a && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--campusguard.evaluation.run=true \
  --campusguard.evaluation.dataset=docs/evaluation-samples.json"
```

Add `--campusguard.evaluation.runs=3` to measure each engine three times and
get a range next to every mean. It costs three times the model calls, and it
is the difference between a comparison and an anecdote.

Omitting `--campusguard.evaluation.dataset` uses the bundled starter set. Every
registered engine runs; one that is unavailable is reported and skipped
rather than ending the run. Machine-readable output lands beside this file as
a machine-readable report and every per-sample answer, under the same stem
(`--campusguard.evaluation.output-prefix`).

Token prices are not built in, because a plausible default would put a number
in this report that nobody checked. Configure them per engine under
`campusguard.evaluation.pricing` to fill in the cost column.
