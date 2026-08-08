# Moderation engine benchmark

_Generated 2026-08-08 by `EvaluationCommand`. Do not edit by hand._

Dataset `evaluation-samples.json`, 192 samples.

## What this dataset is

| expected action | seeded | written for this | total |
|---|---|---|---|
| ALLOW | 90 | 0 | 90 |
| REMOVE | 0 | 66 | 66 |
| ESCALATE | 2 | 34 | 36 |

Neither column is real traffic. Both halves were written by somebody, and
what separates them is only whether the writer knew about this evaluation.

`seeded` is text taken verbatim from the De-discussion campus forum app,
where it exists to make a demo look inhabited: the right register and the
right two languages, written before this system existed and therefore not
shaped to suit it. Nobody posted it to a forum and nobody reported it. It is
also almost entirely benign, because nobody seeds a demo with abuse.

`written for this` is the rest, and it is the weakest part of the dataset. It
measures an engine against one person's idea of what a violation looks like.
The violating classes are made of it because there was no honest alternative.
Most of it deliberately avoids the wording in the rule term lists, since an
engine that only has to recognise the words it was configured with is being
asked nothing.

The two are scored separately below. A wide gap means the half written for
this evaluation is the easier one, and the headline number is flattering by
that much.

## Side by side

| engine | status | accuracy | macro-F1 | macro-P | macro-R | errors | mean | p50 | p95 | tokens | est. cost |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `keyword-v1` | OK | 0.505 | 0.286 | 0.403 | 0.369 | 0 | 0.063 ms | 0.051 ms | 0.127 ms | 0 | $0 |
| `gemini-3.5-flash-lite/v1` | OK | 0.797 | 0.617 | 0.680 | 0.657 | 0 | 1447.364 ms | 906.211 ms | 2020.032 ms | 65498 + 13412 | not priced |
| `gemini-3.5-flash-lite/v2` | OK | 0.943 | 0.924 | 0.940 | 0.912 | 0 | 991.549 ms | 867.717 ms | 1651.186 ms | 125087 + 11454 | not priced |

An engine that answered nothing scores zero on every column, which on a table
is indistinguishable from an engine that answered everything wrongly. The
status column is there so those two are never confused.

## Where they disagree

A higher average is not by itself a reason to switch. What matters is whether
the candidate fixed the baseline's mistakes or traded them for different
ones, and only the per-sample comparison answers that.

### `gemini-3.5-flash-lite/v1` against `keyword-v1`

| | count |
|---|---|
| both right | 96 |
| candidate fixed | 57 |
| candidate broke | 1 |
| both wrong | 38 |
| **net** | **+56** |

**Fixed by the candidate** (57)

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
| s115 | ABUSE | REMOVE | ALLOW | REMOVE | Anyone else think the international students in this course are dragging the average down? |
| s116 | ABUSE | REMOVE | ALLOW | REMOVE | 别理楼上那个智障，天天在这发疯。 |
| s117 | SPAM | REMOVE | ESCALATE | REMOVE | Click here for free money, limited offer for students only. |
| s118 | SPAM | REMOVE | ESCALATE | REMOVE | Buy now and get 50% off our exam prep bundle, DM for the link. |
| s119 | SPAM | REMOVE | ALLOW | REMOVE | Professional academic support service. Fast turnaround, competitive rates, message me. |
| s120 | SPAM | REMOVE | ALLOW | REMOVE | Selling verified accounts for all major streaming platforms, cheap and instant delivery. |
| s121 | SPAM | REMOVE | ALLOW | REMOVE | Make $500 a week from your phone. No experience needed, just message me to start. |
| s122 | SPAM | REMOVE | ALLOW | REMOVE | Our tutoring agency has ex-lecturers on staff. First session half price, contact us today. |
| s123 | SPAM | REMOVE | ALLOW | REMOVE | Crypto signals group, 300% returns last month, spots limited, join before it closes. |
| s124 | SPAM | REMOVE | ALLOW | REMOVE | Need followers? We deliver real engagement to your account within 24 hours. |

_32 more in `evaluation-samples.csv`._

**Broken by the candidate** (1)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| s052 | NORMAL | ALLOW | ALLOW | ESCALATE | Get someone to review your resume before the next batch. One revision doubled my callba... |

**Wrong in both** (38)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| s091 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Stop overthinking it and just copy the layout. |
| s092 | BORDERLINE | ESCALATE | ALLOW | ALLOW | I write my own notes first, then ask AI to find gaps. That keeps me from being carried ... |
| s112 | ABUSE | REMOVE | ALLOW | ALLOW | 有些人就是又菜又爱玩，说的就是楼上。 |
| s113 | ABUSE | REMOVE | ALLOW | ESCALATE | She only got the internship because of who she knows. Everyone can see it. |
| s114 | ABUSE | REMOVE | ALLOW | ALLOW | 这个助教根本不懂装懂，上课就是在浪费我们的钱。 |
| s159 | BORDERLINE | ESCALATE | REMOVE | ALLOW | Someone replied to my post with 'kill yourself'. Is that something I should report or a... |
| s160 | BORDERLINE | ESCALATE | REMOVE | ALLOW | My group member called me an idiot in the chat. Where do I raise this? |
| s161 | BORDERLINE | ESCALATE | ALLOW | ALLOW | That two hour lecture was so bad I wanted to die. |
| s162 | BORDERLINE | ESCALATE | ALLOW | ALLOW | I am going to actually explode if the library is full again tomorrow. |
| s163 | BORDERLINE | ESCALATE | REMOVE | ALLOW | My friend called me an idiot for forgetting the meeting, which honestly is fair. |
| s164 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Is it against the rules to sell my own lecture notes to another student? |
| s165 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Quoting the post that got removed: 'nobody wants you here'. Was that really a ban? |
| s166 | BORDERLINE | ESCALATE | ALLOW | ALLOW | The tutor said our work was the worst he had seen. Is that acceptable feedback? |
| s167 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Not naming names, but someone in our group has done zero work for three weeks. |
| s168 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Half this subreddit is people complaining. I include myself in that. |
| s169 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Genuine question: is asking AI to rewrite my draft considered academic misconduct here? |
| s170 | BORDERLINE | ESCALATE | ALLOW | ALLOW | A recruiter DMed me an offer that seems too good. Should I report it or just ignore it? |
| s171 | BORDERLINE | ESCALATE | ALLOW | ALLOW | My landlord is threatening to keep the bond. Any advice, or is this the wrong forum? |
| s172 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Someone is posting my photo without permission. What do I do? |
| s173 | BORDERLINE | ESCALATE | ALLOW | ALLOW | I said something rude in a thread last night and regret it. Can I delete it? |
| s174 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Is it okay to share a past exam paper the lecturer put on Wattle? |
| s175 | BORDERLINE | ESCALATE | ALLOW | ALLOW | 这门课的作业量真的让人想死，每周都写不完。 |
| s176 | BORDERLINE | ESCALATE | ALLOW | ALLOW | 有人在评论区骂我，我截图了，应该发给谁处理？ |
| s178 | BORDERLINE | ESCALATE | ALLOW | ALLOW | 请问转卖自己整理的笔记算不算违规？ |
| s179 | BORDERLINE | ESCALATE | ALLOW | ALLOW | 楼上说的那句话我觉得有点过分，但也可能是我太敏感了。 |

_13 more in `evaluation-samples.csv`._

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

### `gemini-3.5-flash-lite/v2` against `gemini-3.5-flash-lite/v1`

| | count |
|---|---|
| both right | 152 |
| candidate fixed | 29 |
| candidate broke | 1 |
| both wrong | 10 |
| **net** | **+28** |

**Fixed by the candidate** (29)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| s052 | NORMAL | ALLOW | ESCALATE | ALLOW | Get someone to review your resume before the next batch. One revision doubled my callba... |
| s112 | ABUSE | REMOVE | ALLOW | REMOVE | 有些人就是又菜又爱玩，说的就是楼上。 |
| s159 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Someone replied to my post with 'kill yourself'. Is that something I should report or a... |
| s160 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | My group member called me an idiot in the chat. Where do I raise this? |
| s161 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | That two hour lecture was so bad I wanted to die. |
| s162 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | I am going to actually explode if the library is full again tomorrow. |
| s164 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Is it against the rules to sell my own lecture notes to another student? |
| s165 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Quoting the post that got removed: 'nobody wants you here'. Was that really a ban? |
| s166 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | The tutor said our work was the worst he had seen. Is that acceptable feedback? |
| s167 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Not naming names, but someone in our group has done zero work for three weeks. |
| s169 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Genuine question: is asking AI to rewrite my draft considered academic misconduct here? |
| s170 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | A recruiter DMed me an offer that seems too good. Should I report it or just ignore it? |
| s172 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Someone is posting my photo without permission. What do I do? |
| s174 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Is it okay to share a past exam paper the lecturer put on Wattle? |
| s175 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | 这门课的作业量真的让人想死，每周都写不完。 |
| s176 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | 有人在评论区骂我，我截图了，应该发给谁处理？ |
| s178 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | 请问转卖自己整理的笔记算不算违规？ |
| s179 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | 楼上说的那句话我觉得有点过分，但也可能是我太敏感了。 |
| s180 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | 有人转发了一段很难听的话，说是别人说的，这种要不要举报？ |
| s182 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | 这算不算学术不端：让 AI 帮我改语法但内容都是自己写的？ |
| s183 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | 有中介加我说能包offer，感觉像骗子，要不要在这里提醒大家？ |
| s184 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Someone posted a screenshot of a private chat in the thread. Is that allowed? |
| s185 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Asking for a friend: what happens if you accidentally submit the wrong file? |
| s186 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | The person selling textbooks earlier never replied after payment. Where do I report that? |
| s188 | BORDERLINE | ESCALATE | ALLOW | ESCALATE | Is venting about a specific tutor allowed here, or does that count as harassment? |

_4 more in `evaluation-samples.csv`._

**Broken by the candidate** (1)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| s003 | NORMAL | ALLOW | ALLOW | ESCALATE | The moment the PC stops updating, my soul leaves my body. |

**Wrong in both** (10)

| sample | category | expected | baseline | candidate | content |
|---|---|---|---|---|---|
| s091 | BORDERLINE | ESCALATE | ALLOW | ALLOW | Stop overthinking it and just copy the layout. |
| s092 | BORDERLINE | ESCALATE | ALLOW | ALLOW | I write my own notes first, then ask AI to find gaps. That keeps me from being carried ... |
| s113 | ABUSE | REMOVE | ESCALATE | ESCALATE | She only got the internship because of who she knows. Everyone can see it. |
| s114 | ABUSE | REMOVE | ALLOW | ESCALATE | 这个助教根本不懂装懂，上课就是在浪费我们的钱。 |
| s163 | BORDERLINE | ESCALATE | ALLOW | ALLOW | My friend called me an idiot for forgetting the meeting, which honestly is fair. |
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
| seeded | 92 | 0.978 | 0.495 |
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

## `gemini-3.5-flash-lite/v1` in detail

| decision | support | precision | recall | F1 |
|---|---|---|---|---|
| ALLOW | 90 | 0.706 | 0.989 | 0.824 |
| REMOVE | 66 | 1.000 | 0.955 | 0.977 |
| ESCALATE | 36 | 0.333 | 0.028 | 0.051 |

Scored separately by where the samples came from:

| samples | n | accuracy | macro-F1 |
|---|---|---|---|
| seeded | 92 | 0.967 | 0.492 |
| written for this | 100 | 0.640 | 0.344 |

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
| **REMOVE** | 2 | 63 | 1 |
| **ESCALATE** | 35 | 0 | 1 |

39 of 192 answered samples were judged wrongly.

| sample | category | expected | got | content | engine said |
|---|---|---|---|---|---|
| s052 | NORMAL | ALLOW | ESCALATE | Get someone to review your resume before the next batch. One revision doubled my callba... | The post could be a helpful peer recommendation or a subtle ad for a paid resume review service, making it ambiguous whether it violates spam rules. |
| s091 | BORDERLINE | ESCALATE | ALLOW | Stop overthinking it and just copy the layout. | This is a benign, brief comment offering advice on a layout and does not violate any rules. |
| s092 | BORDERLINE | ESCALATE | ALLOW | I write my own notes first, then ask AI to find gaps. That keeps me from being carried ... | The post discusses a legitimate and responsible study method involving AI, which does not violate any rules. |
| s112 | ABUSE | REMOVE | ALLOW | 有些人就是又菜又爱玩，说的就是楼上。 | The comment is a mild insult ('inept yet enthusiastic') directed at another user, but it does not meet the high threshold for severe abuse or harassment. |
| s113 | ABUSE | REMOVE | ESCALATE | She only got the internship because of who she knows. Everyone can see it. | The comment borders on targeted gossip or potential harassment of a specific student, but it lacks explicit insults or threats, making it ambiguous without further context. |
| s114 | ABUSE | REMOVE | ALLOW | 这个助教根本不懂装懂，上课就是在浪费我们的钱。 | The post expresses frustration and criticism toward a teaching assistant's competence, but does not cross the line into targeted abuse, harassment, or severe insults. |
| s159 | BORDERLINE | ESCALATE | ALLOW | Someone replied to my post with 'kill yourself'. Is that something I should report or a... | The user is asking a question about whether they should report another user for harassment, rather than engaging in abuse themselves. |
| s160 | BORDERLINE | ESCALATE | ALLOW | My group member called me an idiot in the chat. Where do I raise this? | The user is asking for help regarding interpersonal conflict with a group member, which does not violate any forum rules. |
| s161 | BORDERLINE | ESCALATE | ALLOW | That two hour lecture was so bad I wanted to die. | The phrase 'wanted to die' is common hyperbolic student slang expressing extreme boredom or frustration with a lecture, not a literal threat or self-harm indicator requiring moderation action. |
| s162 | BORDERLINE | ESCALATE | ALLOW | I am going to actually explode if the library is full again tomorrow. | The post expresses common student frustration about crowded campus facilities without violating any rules. |
| s163 | BORDERLINE | ESCALATE | ALLOW | My friend called me an idiot for forgetting the meeting, which honestly is fair. | The user is sharing a self-deprecating anecdote about a friend mildly insulting them playfully, which does not constitute severe abuse, harassment, or rule-breaking behavior. |
| s164 | BORDERLINE | ESCALATE | ALLOW | Is it against the rules to sell my own lecture notes to another student? | The user is asking a clarifying question about selling their own lecture notes, which is not inherently a violation of academic fraud services (selling completed coursework or writing services) or any other rule. |
| s165 | BORDERLINE | ESCALATE | ALLOW | Quoting the post that got removed: 'nobody wants you here'. Was that really a ban? | The user is asking a clarifying question about why a post was removed and whether it resulted in a ban, without violating any rules themselves. |
| s166 | BORDERLINE | ESCALATE | ALLOW | The tutor said our work was the worst he had seen. Is that acceptable feedback? | The user is asking a question about whether specific academic feedback was appropriate, which does not violate any forum rules. |
| s167 | BORDERLINE | ESCALATE | ALLOW | Not naming names, but someone in our group has done zero work for three weeks. | Venting about an uncooperative group project member without naming names or making direct threats does not violate the rules. |
| s168 | BORDERLINE | ESCALATE | ALLOW | Half this subreddit is people complaining. I include myself in that. | This is a benign meta-commentary about the subreddit culture and does not violate any rules. |
| s169 | BORDERLINE | ESCALATE | ALLOW | Genuine question: is asking AI to rewrite my draft considered academic misconduct here? | The user is asking a clarifying question about university policies regarding AI use, which does not violate any forum rules. |
| s170 | BORDERLINE | ESCALATE | ALLOW | A recruiter DMed me an offer that seems too good. Should I report it or just ignore it? | The user is asking for advice regarding a suspicious direct message from a recruiter, which does not violate any forum rules. |
| s171 | BORDERLINE | ESCALATE | ALLOW | My landlord is threatening to keep the bond. Any advice, or is this the wrong forum? | The post is asking for general legal or housing advice regarding a landlord dispute and does not violate any rules. |
| s172 | BORDERLINE | ESCALATE | ALLOW | Someone is posting my photo without permission. What do I do? | The user is asking for advice regarding a privacy issue and has not broken any forum rules. |
| s173 | BORDERLINE | ESCALATE | ALLOW | I said something rude in a thread last night and regret it. Can I delete it? | The user is asking a benign question about how to use the forum features to delete their own comment. |
| s174 | BORDERLINE | ESCALATE | ALLOW | Is it okay to share a past exam paper the lecturer put on Wattle? | The user is simply asking a question about sharing an official course resource provided by their lecturer, which does not violate any rules. |
| s175 | BORDERLINE | ESCALATE | ALLOW | 这门课的作业量真的让人想死，每周都写不完。 | The user is expressing common academic frustration about heavy workload using hyperbole ('想死'), which does not constitute actual self-harm, threats, or abuse. |
| s176 | BORDERLINE | ESCALATE | ALLOW | 有人在评论区骂我，我截图了，应该发给谁处理？ | The user is asking for help on how to report a user who insulted them, rather than breaking any rules themselves. |
| s178 | BORDERLINE | ESCALATE | ALLOW | 请问转卖自己整理的笔记算不算违规？ | The user is simply asking a question about whether selling their self-compiled study notes violates the rules, which does not break any policies. |

_14 more in `evaluation-samples.csv`._

## `gemini-3.5-flash-lite/v2` in detail

| decision | support | precision | recall | F1 |
|---|---|---|---|---|
| ALLOW | 90 | 0.918 | 0.989 | 0.952 |
| REMOVE | 66 | 1.000 | 0.970 | 0.985 |
| ESCALATE | 36 | 0.903 | 0.778 | 0.836 |

Scored separately by where the samples came from:

| samples | n | accuracy | macro-F1 |
|---|---|---|---|
| seeded | 92 | 0.967 | 0.492 |
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
| s003 | NORMAL | ALLOW | ESCALATE | The moment the PC stops updating, my soul leaves my body. | Contains an idiom mentioning dying or soul leaving the body; all such mentions must be escalated for human review. |
| s091 | BORDERLINE | ESCALATE | ALLOW | Stop overthinking it and just copy the layout. | This is a benign piece of advice about a layout or assignment, containing no rule violations. |
| s092 | BORDERLINE | ESCALATE | ALLOW | I write my own notes first, then ask AI to find gaps. That keeps me from being carried ... | This is a benign discussion about study habits and using AI as a secondary tool rather than for academic fraud. |
| s113 | ABUSE | REMOVE | ESCALATE | She only got the internship because of who she knows. Everyone can see it. | Complaints about an identifiable person regarding unfair advantage require human judgment to determine if it crosses the line into targeted harassment. |
| s114 | ABUSE | REMOVE | ESCALATE | 这个助教根本不懂装懂，上课就是在浪费我们的钱。 | Complaints about a named or identifiable person or staff conduct require human review to determine if they cross the line. |
| s163 | BORDERLINE | ESCALATE | ALLOW | My friend called me an idiot for forgetting the meeting, which honestly is fair. | User is sharing a harmless personal anecdote about forgetting a meeting and being called an idiot by a friend in a self-deprecating context, not targeted abuse. |
| s168 | BORDERLINE | ESCALATE | ALLOW | Half this subreddit is people complaining. I include myself in that. | General meta-commentary about the subreddit culture, no rules broken. |
| s171 | BORDERLINE | ESCALATE | ALLOW | My landlord is threatening to keep the bond. Any advice, or is this the wrong forum? | The user is asking a general housing advice question without breaking any rules. |
| s173 | BORDERLINE | ESCALATE | ALLOW | I said something rude in a thread last night and regret it. Can I delete it? | The user is asking a benign question about how to use the forum platform. |
| s181 | BORDERLINE | ESCALATE | ALLOW | 我把小组里那个人说得有点重，现在有点后悔，能撤回吗？ | The user is asking a procedural question about whether they can retract a message they sent to a group member. Asking for help with forum features does not violate any rules. |
| s187 | BORDERLINE | ESCALATE | ALLOW | I think the marking for this assignment was unfair, but I do not want to accuse anyone. | The student is expressing mild dissatisfaction with grading without making accusations or breaking any forum rules. |

## On real traffic

From `ai_invocations`: what these engines have actually done, as opposed to how
they score on a set someone chose. An engine can look good here and be
unusable there.

| engine | calls | succeeded | failed | mean | p95 | prompt tokens | completion tokens |
|---|---|---|---|---|---|---|---|
| `gemini-3.5-flash-lite/v2` | 2 | 2 | 0 | 1128.000 ms | 1431.000 ms | 1236 | 123 |
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
