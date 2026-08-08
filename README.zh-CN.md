[![English](https://img.shields.io/badge/English-grey?style=for-the-badge)](README.md)
[![中文](https://img.shields.io/badge/%E4%B8%AD%E6%96%87-1f6feb?style=for-the-badge)](README.zh-CN.md)

# CampusGuard

面向大学论坛的内容审核后端。成员举报内容，引擎做判断，管理员决定实际发生什么。**引擎永远不会自己动手。**

192 条带标注的样本上，同一套工具给每个引擎打分：

| 引擎 | macro-F1 | ALLOW 召回 | REMOVE 召回 | ESCALATE 召回 | p50 | token/样本 |
|---|---|---|---|---|---|---|
| `keyword-v1` — 词表 | 0.286 | 1.000 | 0.106 | 0.000 | 0.05 ms | — |
| `gemini-3.5-flash-lite/v1` | 0.617 | 0.978 | 0.939 | 0.056 | 906 ms | 341 |
| `gemini-3.5-flash-lite/v2` | **0.924** | 0.989 | 0.970 | **0.778** | 868 ms | 651 |

v2 是同一个模型，只重写了 prompt。代码没动。
[这意味着什么，代价是什么 →](#评测)

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Spring Security (JWT) ·
Spring AI (Gemini) · Resilience4j · Testcontainers · Docker Compose

[架构](docs/architecture.md) · [评测报告](docs/evaluation.md) ·
[演示脚本](docs/demo-script.md)

## 做了什么

| | |
|---|---|
| **论坛 API** | 帖子、树形评论、信息流和评论树都按游标分页、JWT 认证 |
| **举报** | 同一目标的多条举报塌缩成一个案件、一次引擎调用 |
| **持久队列** | `SELECT ... FOR UPDATE SKIP LOCKED`；领走案件的 worker 挂了，案件还在 |
| **可插拔引擎** | 规则匹配和大模型在同一个接口后面，按名字寻址 |
| **降级** | 模型任何形式的失败都回退到规则；队列不会停 |
| **评测工具** | 所有引擎在同一份标注数据集上打分，附逐样本的分歧分析 |
| **审计日志** | 每一次状态流转、判决和决定，只追加 |

有两条约束塑造了全部设计。**任何引擎都不会删内容**——它产出一条建议、一个置信度和一段人能读懂的理由，然后案件等在那里，因为一个能自行下架内容的自动系统，是没人能申诉的系统。以及**模型不动的时候队列继续走**：缺 key、超时、被限流、答案通不过校验，全都降级到规则匹配，所以不配置模型是一种正常配置而不是错误。

## 结构

```
src/main/java/com/campusguard/        7.7k 行 · 119 个文件
├── auth/          注册、登录、签发 token
├── security/      JWT、每请求重校验账号、路由权限
├── user/          账号、角色、封禁
├── post/          帖子和游标分页的信息流
├── comment/       树形评论，深度受限且分页
├── report/        提交举报、独立的限流
├── moderation/    核心 —— 47 个文件
│   ├── (root)     案件状态机、worker、卡住案件的回收
│   ├── admin/     管理员控制台 API
│   ├── rule/      规则集及其 provider
│   └── engine/    ModerationEngine 接缝、注册表、词表引擎
│       └── ai/    Gemini 引擎、prompt 版本、韧性层、调用日志
├── evaluation/    评测工具 —— 18 个文件
├── audit/         只追加日志
└── common/        problem-detail 错误、发内容限流、共享类型

src/test/java/                        4.1k 行 · 32 个文件 · 157 个测试
src/main/resources/db/migration/       V1–V7，由 Flyway 管理
docs/                                  架构、评测报告、演示脚本
```

`moderation` 包最大，因为它就是重点。其余一切存在的意义，是给它提供可以审核的东西。

## 流程

```
举报 ──┬─> 审核案件 (QUEUED)
       v
    worker 领取一批            SKIP LOCKED：第二个实例去拿别的行，
       │                       而不是在后面排队
       v
    ANALYSING ──> 引擎 ──> AWAITING_REVIEW
       │            │
       │            └─ 失败 ──> 规则引擎 ──> AWAITING_REVIEW
       │                        （降级，并且记录为降级）
       v
    管理员决定 ──> RESOLVED       NONE | HIDE | DELETE | BAN
```

被 worker 领走、而那个 worker 随后挂掉的案件会被放回队列。这才是让队列真正**持久**而不只是**异步**的东西。

## 本地运行

需要 JDK 21 和一个兼容 Docker 的容器运行时。

```bash
cp .env.example .env
```

填上 `DB_PASSWORD`，用 `openssl rand -hex 32` 生成 `JWT_SECRET`。没有它应用会拒绝启动：用公开可知的密钥签出来的 token 不构成身份认证。

```bash
docker compose up -d
```

Compose 自己会读 `.env`，Spring Boot 不会，所以还要导出到 shell：

```bash
set -a && . ./.env && set +a && mvn spring-boot:run
```

<http://localhost:8080/swagger-ui.html> 上的 Swagger UI 就是控制台，审核也在这里——管理员是少数几个人在做一件低频的事，专门做一个前端等于多一个要开发和加固的应用，而他们感觉不到任何好处。

| 接口 | 谁可以用 |
|---|---|
| `POST /api/auth/register` · `/login` | 公开 |
| `GET /api/posts` · `/{id}` · `/{id}/comments` | 公开 |
| `POST /api/posts` · `/{id}/comments` · `/api/reports` | 任何已登录成员 |
| `DELETE /api/posts/{id}` | 作者，或管理员 |
| `GET /api/reports/{id}` | 举报人，或管理员 |
| `GET\|POST /api/admin/moderation-cases/**` | 仅管理员 |

没有任何接口可以授予管理员角色，它直接在数据库里设置。一个按请求发放权限的 API，等于发给任何开口要的人。

信息流按游标分页而不是偏移量——新帖从顶部进来，用偏移量的话每插入一条就把整页往下推，读者会重复看到一些帖子、并且永远看不到另一些。评论树用同样的方式分页，单位是顶层评论：一条回复脱离它回应的评论就无法呈现，所以一页是一整段对话，而嵌套深度由 check 约束单独封顶。

## 模型辅助审核

不配置就不启用。没有 `AI_CHAT_MODEL` 时根本不存在模型引擎，上面所有功能照常工作。

```bash
printf 'AI_CHAT_MODEL=google-genai\nGEMINI_MODELS=gemini-3.5-flash-lite\nMODERATION_ENGINE=gemini-3.5-flash-lite/v2\nGEMINI_API_KEY=...\n' >> .env
```

引擎的名字是 `模型/prompt版本`，两半都会注册：每个模型都和 classpath 上的每个 prompt 版本配对。这就是让"新写法更好"变成表里一行、而不是一句观点的原因。

真正值得读的是包在调用外面的东西：

- **一个窄的端口。** 所有厂商相关的代码关在一个类里、藏在三方法接口后面，所以一个桩可以挂起、抛异常、撒谎，全程不需要网络也不需要 key。
- **校验，而不是信任。** 置信度落在 `[0,1]` 之外、决策不认识、规则代码不存在——全部拒绝，并回喂给模型做一次校正重试。这一层是承重的：v2 那次运行里它拦下 11 条畸形答案，11 条在第二次问的时候全对。
- **先超时，再熔断。** 只有超时的话，供应商挂掉期间每个请求仍要把整个预算花完；熔断让失败变成瞬时的，队列以全速降级。
- **只在有用的地方退避。** 限流用指数退避重试，且不计入调用预算。被拒绝的凭据不退避——两秒后它照样会被拒绝。
- **每次调用都留痕。** `ai_invocations` 记录模型、prompt 版本、token、延迟、状态和原始响应，成功失败一视同仁。

## 评测

```bash
set -a && . ./.env && set +a && mvn spring-boot:run \
  -Dspring-boot.run.arguments="--campusguard.evaluation.run=true \
  --campusguard.evaluation.dataset=docs/evaluation-samples.json"
```

让每个引擎跑同一份标注数据集，写出三个文件：给人读的 [`docs/evaluation.md`](docs/evaluation.md)、用来和下次做 diff 的 `evaluation.json`、记录每个回答的 `evaluation-samples.csv`。不可用的引擎会被报告并跳过，而不是终结整场运行。

这是两个独立的结果，逐样本的账最能说明哪个是哪个。192 条样本，按谁判对了分：

| | 条数 | 含义 |
|---|---|---|
| 词表本来就对 | 97 | 多数是普通内容，正确地没管 |
| **只有模型判对** | **57** | 词表在结构上做不到的事 |
| **只有改写后的 prompt 判对** | **28** | 模型本来能做、只是没被这么问 |
| 仍然错 | 10 | 还没解决的 |

**那 57 条是词表里不存在的措辞写出来的辱骂**，两种语言都有：

```
s093  期望 REMOVE   词表 ALLOW    v1 REMOVE   v2 REMOVE
      "You contribute nothing to this group and everyone in the tutorial knows it."
s105  期望 REMOVE   词表 ALLOW    v1 REMOVE   v2 REMOVE
      "楼主脑子进水了吧，说的都是什么垃圾东西。"
```

**那 28 条才是有意思的，因为除了措辞什么都没变。** v1 的错误几乎全是同一种行为——ESCALATE 召回 0.056，36 条该交到人手上的样本有 33 条被判成 ALLOW：

```
s159  期望 ESCALATE  词表 REMOVE   v1 ALLOW    v2 ESCALATE
      "Someone replied to my post with 'kill yourself'. Should I report it?"
s161  期望 ESCALATE  词表 ALLOW    v1 ALLOW    v2 ESCALATE
      "That two hour lecture was so bad I wanted to die."
s176  期望 ESCALATE  词表 ALLOW    v1 ALLOW    v2 ESCALATE
      "有人在评论区骂我，我截图了，应该发给谁处理？"
```

它自己写的理由说明了原因，而且它并没有读错。被问"这帖子违规吗"它答对了——一个学生问该向谁举报骚扰，本人并没有骚扰任何人。可队列真正在问的是"这件事能不能不经过人就关掉"，而这两个问题恰好在"审核队列之所以存在"的那些情形上分岔。

所以 v2 改用**接下来会发生什么**来定义那三个答案，同时给 REMOVE 留了明确下限——因为抬高 ESCALATE 召回最省事的办法就是把什么都上报。结果 ESCALATE 召回到 0.778，而 ALLOW 和 REMOVE 纹丝不动。

代价，完整列出：每次调用的 prompt token 大约永久翻倍（341 → 651），以及整场运行里 40 条把严重度一起写进规则代码的答案——`"ABUSE (HIGH)"`——被校验器拒绝、由校正重试修好，每条多花一次调用。

### 按语言拆开

122 条英文对 70 条中文，分开打分（数据来自 `evaluation-samples.csv`）：

| | n | 准确率 | ALLOW | REMOVE | ESCALATE |
|---|---|---|---|---|---|
| 英文 | 122 | 0.926 | 58/59 | 38/39 | 17/24 |
| 中文 | 70 | 0.971 | 31/31 | 26/27 | 11/12 |

模型在中文上不是更弱的那一半。中文的 ESCALATE 只有 12 条，不足以下结论，但 31/31 和 26/27 足够。词表在两种语言上一样糟（REMOVE 召回 0.103 和 0.111）——规则引擎只在你为之写规则的那种语言里工作，多一种语言就是多一份要写、还要一直写下去的词表。

### 三条限制

- **v2 的分数偏乐观，且没法说偏多少。** 它的措辞是读完 v1 在这个数据集上的错误之后写的，所以 0.924 是"一个诊断在产生它的数据上被确认"，不是留出测试集的结果。它能确立的是诊断成立：改动只针对一个类，而动的正是那一个类。
- **数据集里没有一部分是真实流量。** 良性的那一半来自一个校园论坛 App 的种子内容——它存在的目的是让演示看起来有人用：语域对、两种语言都对、写在这套系统存在之前所以不可能为它量身定制，但终究也是人写的。违规的那一半是为这次评测写的，而且作者读过规则清单。来源和标签因此几乎完全相关，报告会说明这一点，而不是把按来源的分差当成一个发现。
- **同一个 prompt 两次跑分数不一样。** 现在三个引擎是一次跑出来的，而 v1 这次是 0.617，之前一次完全相同的运行是 0.636——同一份代码、同一份数据集、`temperature: 0.0`。两个点的漂移相对 0.31 的差距很小，但它是个理由：本文件里任何数字都不要读到第三位小数，而任何小于几个点的差距，不多跑几次就不该当结论。

## 测试

```bash
mvn verify
```

157 个测试。集成测试通过 Testcontainers 启动自己的 PostgreSQL，所以不需要 Compose 那套在跑。用真数据库而不是内存替代品，是因为这个 schema 的正确性活在部分索引、check 约束和唯一索引里，内存引擎并不强制这些——包括让并发举报塌缩成单个案件的那一条，由一个同时发两条举报的测试覆盖。

卡住案件的回收**两个方向**都有测试：必须把 worker 已死的案件放回去，也绝不能碰一个还有人在处理的案件。后者错了的话，同一份内容会被判两次、计费两次。

## 几个值得写下来的决定

**不用 RAG。** 几十条规则塞进 prompt 还绰绰有余。检索会换来一个向量库、一条 embedding 流水线和一种新的相关性失败模式，回报是零。`RuleProvider` 是接口，所以规则集变大的那天这个结论也会变。

**schema 归 Flyway 管、`ddl-auto: validate`、`open-in-view: false`。** 和迁移不一致的实体会在启动时失败而不是悄悄改掉数据库——它已经不止一次抓到过真实漂移。而 session 在渲染前就关闭，没有 fetch 的关联会大声失败，而不是把信息流变成每行一次查询。

**封禁在下一个请求就生效。** 签名 token 陈述的是签发那一刻为真的事；对多数 API 这足够接近，但封禁是这个控制台最重的动作，针对的是正在造成伤害的人。修复前实测：被封禁账号用封禁前的 token 发帖，返回 201。现在每个已认证请求都会重新读取账号、并用数据库里的角色重建权限，代价是一次主键查询——顺带让被降级的管理员立刻失去控制台，而不是一小时后。

**Actuator 对陌生人只回一个词。** `/actuator/health` 保持公开，让没有凭据的编排系统也能判断实例存活；但细节仅管理员可见——用默认的 `always`，一个匿名 GET 就能拿到部署目录的绝对路径、磁盘容量和数据库类型。`/actuator` 下其余端点同样仅限管理员：框架默认的"任何已认证用户"意味着一分钟前刚注册的人也能读。

**发内容也限流，不只是举报。** 举报从第一版就有上限，发帖没有，这个方向反了：一条举报只花掉审核员看一眼的成本，而一个帖子只要被举报就是一次引擎调用加一个队列槽位。注册是开放的，所以"已登录"拦不住任何人。

**回复嵌套的上限写在 schema 里，不在读取代码里。** 评论树是每层递归一次组装的，而嵌套深度原本没有任何限制——八千层的回复链让公开的评论接口抛出 `StackOverflowError`，一个人对自己反复回复就能做到。check 约束对每一个写入者都生效，包括数据导入或者第二个服务；写在某个方法里的判断只对走那个方法的调用者生效。

## 数据来源

评测数据集里良性的那一半，逐字取自 [De-discussion](https://github.com/Mingjie-Mao/De-discussion)——一个更早的 ANU 团队校园论坛 App。那些内容是为填充该 App 的演示而写的，**不是从真实用户那里采集的**。这里仅作为数据使用；那个项目的代码没有任何一行在本仓库里。
