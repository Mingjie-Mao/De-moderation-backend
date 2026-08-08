[![English](https://img.shields.io/badge/English-grey?style=for-the-badge)](README.md)
[![中文](https://img.shields.io/badge/%E4%B8%AD%E6%96%87-1f6feb?style=for-the-badge)](README.zh-CN.md)

# CampusGuard

面向大学论坛的内容审核后端。成员举报内容，引擎做判断，管理员决定实际发生什么。**引擎永远不会自己动手。**

192 条带标注的样本上，同一套工具给每个引擎打分：

| 引擎 | macro-F1 | ALLOW 召回 | REMOVE 召回 | ESCALATE 召回 | p50 | token/样本 |
|---|---|---|---|---|---|---|
| `keyword-v1` — 词表 | 0.286 | 1.000 | 0.106 | 0.000 | 0.3 ms | — |
| `gemini-3.5-flash-lite/v1` | 0.636 | 0.989 | 0.970 | 0.056 | 871 ms | 336 |
| `gemini-3.5-flash-lite/v2` | **0.924** | 0.989 | 0.970 | **0.778** | 858 ms | 655 |

v2 是同一个模型，只重写了 prompt。代码没动。
[这意味着什么，代价是什么 →](#评测)

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Spring Security (JWT) ·
Spring AI (Gemini) · Resilience4j · Testcontainers · Docker Compose

[架构](docs/architecture.md) · [评测报告](docs/evaluation.md) ·
[演示脚本](docs/demo-script.md)

## 做了什么

| | |
|---|---|
| **论坛 API** | 帖子、评论、游标分页的信息流、JWT 认证 |
| **举报** | 同一目标的多条举报塌缩成一个案件、一次引擎调用 |
| **持久队列** | `SELECT ... FOR UPDATE SKIP LOCKED`；领走案件的 worker 挂了，案件还在 |
| **可插拔引擎** | 规则匹配和大模型在同一个接口后面，按名字寻址 |
| **降级** | 模型任何形式的失败都回退到规则；队列不会停 |
| **评测工具** | 所有引擎在同一份标注数据集上打分，附逐样本的分歧分析 |
| **审计日志** | 每一次状态流转、判决和决定，只追加 |

## 塑造了一切的两条约束

**任何引擎都不会删内容。** 它产出一条建议、一个置信度，和一段人能读懂的理由，然后案件等在那里。一个能自行下架内容的自动系统，是没人能申诉的系统。

**模型不动的时候队列继续走。** 缺 API key、超时、被限流、答案通不过校验，全都降级到规则匹配。完全不配置模型是一种正常配置，不是错误。

## 结构

```
src/main/java/com/campusguard/        7.3k 行 · 115 个文件
├── auth/          注册、登录、签发 token
├── security/      JWT 过滤器、principal 解析、方法级权限
├── user/          账号、角色、封禁
├── post/          帖子和游标分页的信息流
├── comment/       评论
├── report/        提交举报、按用户限流
├── moderation/    核心 —— 47 个文件
│   ├── (root)     案件状态机、worker、卡住案件的回收
│   ├── admin/     管理员控制台 API
│   ├── rule/      规则集及其 provider
│   └── engine/    ModerationEngine 接缝、注册表、词表引擎
│       └── ai/    Gemini 引擎、prompt 版本、韧性层、调用日志
├── evaluation/    评测工具 —— 18 个文件
├── audit/         只追加日志
└── common/        problem-detail 错误、共享类型

src/test/java/                        3.5k 行 · 29 个文件 · 141 个测试
src/main/resources/db/migration/       V1–V6，由 Flyway 管理
docs/                                  架构、评测报告、演示脚本
```

`moderation` 包最大，因为它就是重点。其余一切存在的意义，是给它提供可以审核的东西。

## 流程

```
举报 ──┬─> 审核案件 (QUEUED)          同一目标的多条举报
       │                              合并成一个案件、一次引擎调用
       v
    worker 领取一批                    SKIP LOCKED，第二个实例会去拿
       │                              别的行，而不是排在后面等
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

信息流按游标分页而不是偏移量——新帖从顶部进来，用偏移量的话每插入一条就把整页往下推，读者会重复看到一些帖子、并且永远看不到另一些。

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

顶部那张表其实是两个独立的结果。

**模型赢在规则打不了补丁的地方。** 词表漏掉的 59 条样本 v1 判对了——那些用任何词表里都没有的措辞写出来的辱骂，论坛用的两种语言里都有。

**Prompt 赢过模型。** v1 剩下的错误几乎全是同一种行为：ESCALATE 召回 0.056，36 条该交到人手上的样本有 33 条被判成 ALLOW。它自己写的理由说明了原因——它被问"这帖子违规吗"并且答对了，可队列真正在问的是"这件事能不能不经过人就关掉"。"有人未经允许发我的照片，我该怎么办？"没有违反任何规则，但仍然需要一个人。

v2 改用**接下来会发生什么**来定义那三个答案，并给 REMOVE 留了一条明确的下限，因为抬高 ESCALATE 召回最省事的办法就是把什么都上报。结果 ESCALATE 召回到 0.778，而 ALLOW 和 REMOVE 纹丝不动。代价是每次调用的 prompt token 翻倍、3 条样本被过度上报、11 条畸形答案必须重问一次。

论坛用英文和中文两种语言，数据集是 122 条英文对 70 条中文。分开打分（数据来自 `evaluation-samples.csv`）：

| | n | 准确率 | ALLOW | REMOVE | ESCALATE |
|---|---|---|---|---|---|
| 英文 | 122 | 0.926 | 58/59 | 38/39 | 17/24 |
| 中文 | 70 | 0.971 | 31/31 | 26/27 | 11/12 |

模型在中文上并不更差——这里甚至更好，不过中文的 ESCALATE 只有 12 条，不足以下结论。词表在两种语言上一样糟（REMOVE 召回 0.103 和 0.111），而这正是重点：规则引擎只在你为之写规则的那种语言里工作，多一种语言就意味着多写并长期维护一份词表。

三条限制，明说出来而不是等人发现：

- **v2 的分数偏乐观，且没法说偏多少。** 它的措辞是读完 v1 在这个数据集上的错误之后写的，所以 0.924 是"一个诊断在产生它的数据上被确认"，不是留出测试集的结果。它确实能确立的是诊断成立：改动只针对一个类，而动的正是那一个类。
- **数据集里违规的那一半是为它写的。** 良性的那一半是真实论坛内容；一个种子演示应用里没有辱骂可以采样。来源和标签因此几乎完全相关，报告会说明这一点，而不是把按来源的分差当成一个发现来呈现。
- **三个引擎是分两次跑出来的。** 免费额度每天 500 次请求，三个引擎跑 192 条样本需要 588 次。v1 那一行来自上一次运行，在 git 历史里，同一份数据集、同一份代码。

## 测试

```bash
mvn verify
```

141 个测试。集成测试通过 Testcontainers 启动自己的 PostgreSQL，所以不需要 Compose 那套在跑。用真数据库而不是内存替代品，是因为这个 schema 的正确性活在部分索引、check 约束和唯一索引里，内存引擎并不强制这些——包括让并发举报塌缩成单个案件的那一条，由一个同时发两条举报的测试覆盖。

卡住案件的回收**两个方向**都有测试：必须把 worker 已死的案件放回去，也绝不能碰一个还有人在处理的案件。后者错了的话，同一份内容会被判两次、计费两次。

## 几个值得写下来的决定

**不用 RAG。** 几十条规则塞进 prompt 还绰绰有余。检索会换来一个向量库、一条 embedding 流水线和一种新的相关性失败模式，回报是零。`RuleProvider` 是接口，所以规则集变大的那天这个结论也会变。

**schema 归 Flyway 管，`ddl-auto: validate`。** 和迁移不一致的实体会在启动时失败，而不是悄悄改掉数据库。它已经不止一次抓到过真实的漂移。

**`open-in-view: false`。** 开着的话懒加载问题会被掩盖；关掉之后，没有 fetch 的关联会大声失败，而不是把一个信息流变成每行一次查询。

**封禁在下一个请求就生效。** 签名 token 陈述的是签发那一刻为真的事；对多数 API 这足够接近，但封禁是这个控制台最重的动作，针对的是正在造成伤害的人。修复前实测：被封禁账号用封禁前的 token 发帖，返回 201。现在每个已认证请求都会重新读取账号、并用数据库里的角色重建权限，代价是一次主键查询——顺带让被降级的管理员立刻失去控制台，而不是一小时后。

**Actuator 对陌生人只回一个词。** `/actuator/health` 保持公开，因为编排系统没有凭据也得能判断实例是否存活；但细节仅管理员可见——用默认的 `always`，一个匿名 GET 就能拿到部署目录的绝对路径、磁盘容量和数据库类型。`/actuator` 下的其余端点同样仅限管理员：框架默认的"任何已认证用户"意味着一分钟前刚注册的成员也能读。

## 数据来源

评测数据集里良性的那一半，是从更早的 ANU 团队项目 [De-discussion](https://github.com/Mingjie-Mao/De-discussion) 逐字取来的种子论坛内容。仅作为数据使用；那个项目的代码没有任何一行在本仓库里。
