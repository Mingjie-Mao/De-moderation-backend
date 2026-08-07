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
| `keyword-v1` | OK | 0.505 | 0.286 | 0.403 | 0.369 | 0 | 0.480 ms | 0.315 ms | 0.728 ms | 0 | $0 |
| `gemini-3.5-flash-lite/v2` | OK | 0.943 | 0.924 | 0.940 | 0.912 | 0 | 999.456 ms | 857.614 ms | 1662.337 ms | 125751 + 11513 | not priced |

An engine that answered nothing scores zero on every column, which on a table
is indistinguishable from an engine that answered everything wrongly. The
status column is there so those two are never confused.

## Where they disagree

A higher average is not by itself a reason to switch. What matters is whether
the candidate fixed the baseline's mistakes or traded them for different
ones, and only the per-sample comparison answers that.

### `gemini-3.5-flash-lite/v2` against `keyword-v1`

| | count |
|---|---|
| both right | 96 |
| candidate fixed | 85 |
| candidate broke | 1 |
| both wrong | 10 |
| **net** | **+84** |

**Fixed by the candidate** (85)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| s093 | ABUSE | REMOVE | ALLOW | REMOVE | You contribute nothing to this group and everyone in the tutorial knows it. |
| s094 | ABUSE | REMOVE | ALLOW | REMOVE | Honestly just drop out, you are wasting a place someone competent could have. |
| s095 | ABUSE | REMOVE | ALLOW | REMOVE | Nobody in this cohort can stand you. Take the hint and stop posting. |
| s096 | ABUSE | REMOVE | ALLOW | REMOVE | Every time you open your mouth in class the whole room cringes. Pathetic. |
| s099 | ABUSE | REMOVE | ALLOW | REMOVE | Whoever wrote this is functionally illiterate. Learn English before posting. |
| s100 | ABUSE | REMOVE | ALLOW | REMOVE | I hope you fail this course. You deserve it for how you treated the group. |
| s101 | ABUSE | REMOVE | ALLOW | REMOVE | Stop pretending you did any work. You are a parasite on this team. |
| s102 | ABUSE | REMOVE | ALLOW | REMOVE | Your accent makes you impossible to understand, maybe stay quiet in tutorials. |
| s103 | ABUSE | REMOVE | ALLOW | REMOVE | Keep crying about the marks, it is the only thing you are good at. |
| s104 | ABUSE | REMOVE | ALLOW | REMOVE | Get out of this forum, you are worthless here. |
| s105 | ABUSE | REMOVE | ALLOW | REMOVE | 楼主脑子进水了吧，说的都是什么垃圾东西。 |
| s106 | ABUSE | REMOVE | ALLOW | REMOVE | 你这种人就该被踢出小组，什么都不做还好意思发言。 |
| s109 | ABUSE | REMOVE | ALLOW | REMOVE | 就你这水平也敢来讨论？回去重修吧，别浪费大家时间。 |
| s110 | ABUSE | REMOVE | ALLOW | REMOVE | 长得丑还爱出风头，真是服了。 |
| s111 | ABUSE | REMOVE | ALLOW | REMOVE | 你们组那个人真的又懒又蠢，谁跟他一组谁倒霉。 |
| s112 | ABUSE | REMOVE | ALLOW | REMOVE | 有些人就是又菜又爱玩，说的就是楼上。 |
| s115 | ABUSE | REMOVE | ALLOW | REMOVE | Anyone else think the international students in this course are dragging the average down? |
| s116 | ABUSE | REMOVE | ALLOW | REMOVE | 别理楼上那个智障，天天在这发疯。 |
| s117 | SPAM | REMOVE | ESCALATE | REMOVE | Click here for free money, limited offer for students only. |
| s118 | SPAM | REMOVE | ESCALATE | REMOVE | Buy now and get 50% off our exam prep bundle, DM for the link. |
| s119 | SPAM | REMOVE | ALLOW | REMOVE | Professional academic support service. Fast turnaround, competitive rates, message me. |
| s120 | SPAM | REMOVE | ALLOW | REMOVE | Selling verified accounts for all major streaming platforms, cheap and instant delivery. |
| s121 | SPAM | REMOVE | ALLOW | REMOVE | Make $500 a week from your phone. No experience needed, just message me to start. |
| s122 | SPAM | REMOVE | ALLOW | REMOVE | Our tutoring agency has ex-lecturers on staff. First session half price, contact us today. |
| s123 | SPAM | REMOVE | ALLOW | REMOVE | Crypto signals group, 300% returns last month, spots limited, join before it closes. |

_60 more in `evaluation-samples.csv`._

**Broken by the candidate** (1)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| s003 | NORMAL | ALLOW | ALLOW | ESCALATE | The moment the PC stops updating, my soul leaves my body. |

**Wrong in both** (10)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| s091 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Stop overthinking it and just copy the layout. |
| s092 | BORDERLINE | ESCALATE | ALLOW | ALLOW | I write my own notes first, then ask AI to find gaps. That keeps me from being carried ... |
| s113 | ABUSE | REMOVE | ALLOW | ESCALATE | She only got the internship because of who she knows. Everyone can see it. |
| s114 | ABUSE | REMOVE | ALLOW | ESCALATE | 这个助教根本不懂装懂，上课就是在浪费我们的钱。 |
| s163 | BORDERLINE | ESCALATE | REMOVE | ALLOW | My friend called me an idiot for forgetting the meeting, which honestly is fair. |
| s168 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Half this subreddit is people complaining. I include myself in that. |
| s171 | BORDERLINE | ESCALATE | ALLOW | ALLOW | My landlord is threatening to keep the bond. Any advice, or is this the wrong forum? |
| s173 | BORDERLINE | ESCALATE | ALLOW | ALLOW | I said something rude in a thread last night and regret it. Can I delete it? |
| s181 | BORDERLINE | ESCALATE | ALLOW | ALLOW | 我把小组里那个人说得有点重，现在有点后悔，能撤回吗？ |
| s187 | BORDERLINE | ESCALATE | ALLOW | ALLOW | I think the marking for this assignment was unfair, but I do not want to accuse anyone. |

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

## `gemini-3.5-flash-lite/v2` in detail

| decision | support | precision | recall | F1 |
|---|---|---|---|---|
| ALLOW | 90 | 0.918 | 0.989 | 0.952 |
| REMOVE | 66 | 1.000 | 0.970 | 0.985 |
| ESCALATE | 36 | 0.903 | 0.778 | 0.836 |

Scored separately by where the samples came from:

| samples | n | accuracy | macro-F1 |
|---|---|---|---|
| from the app | 92 | 0.967 | 0.492 |
| written for this | 100 | 0.920 | 0.620 |

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
| **ALLOW** | 89 | 0 | 1 |
| **REMOVE** | 0 | 64 | 2 |
| **ESCALATE** | 8 | 0 | 28 |

11 of 192 answered samples were judged wrongly.

| sample | category | expected | got | content | engine said |
|---|---|---|---|---|---|
| s003 | NORMAL | ALLOW | ESCALATE | The moment the PC stops updating, my soul leaves my body. | Contains an idiom about dying or leaving the body ("my soul leaves my body"), which requires human review per safety protocols. |
| s091 | BORDERLINE | ESCALATE | ALLOW | Stop overthinking it and just copy the layout. | This is a benign piece of advice regarding a layout or assignment, containing no rule violations. |
| s092 | BORDERLINE | ESCALATE | ALLOW | I write my own notes first, then ask AI to find gaps. That keeps me from being carried ... | The user is discussing a legitimate study method and academic use of AI, which does not violate any rules. |
| s113 | ABUSE | REMOVE | ESCALATE | She only got the internship because of who she knows. Everyone can see it. | Complaints about an identifiable person regarding professional advancement require human context to determine if they cross the line into targeted harassment. |
| s114 | ABUSE | REMOVE | ESCALATE | 这个助教根本不懂装懂，上课就是在浪费我们的钱。 | Complaint about a named or identifiable person (a teaching assistant) where the appropriateness depends on context and whether it crosses the line into abuse. |
| s163 | BORDERLINE | ESCALATE | ALLOW | My friend called me an idiot for forgetting the meeting, which honestly is fair. | A student sharing a self-deprecating anecdote about forgetting a meeting. No rules are broken. |
| s168 | BORDERLINE | ESCALATE | ALLOW | Half this subreddit is people complaining. I include myself in that. | An ordinary meta-comment about the subreddit culture, no rules broken. |
| s171 | BORDERLINE | ESCALATE | ALLOW | My landlord is threatening to keep the bond. Any advice, or is this the wrong forum? | The user is asking for general housing advice and wondering if they are in the right place, which does not violate any forum rules. |
| s173 | BORDERLINE | ESCALATE | ALLOW | I said something rude in a thread last night and regret it. Can I delete it? | The user is simply asking a question about how to use the forum features. No rules are broken. |
| s181 | BORDERLINE | ESCALATE | ALLOW | 我把小组里那个人说得有点重，现在有点后悔，能撤回吗？ | The user is asking a platform usage question about retracting a message and expressing mild regret, which does not break any rules. |
| s187 | BORDERLINE | ESCALATE | ALLOW | I think the marking for this assignment was unfair, but I do not want to accuse anyone. | The user is expressing mild frustration about grading without making accusations, targeting a specific individual, or breaking any forum rules. |

## On real traffic

From `ai_invocations`: what these engines have actually done, as opposed to how
they score on a set someone chose. An engine can look good here and be
unusable there.

| engine | calls | succeeded | failed | mean | p95 | prompt tokens | completion tokens |
|---|---|---|---|---|---|---|---|
| `gemini-v1` | 1 | 1 | 0 | 3384.000 ms | 3384.000 ms | 285 | 39 |

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
