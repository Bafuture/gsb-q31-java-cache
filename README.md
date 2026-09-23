# 从零实现进程内本地缓存组件

Pair-wise GSB 标注任务仓库（第 4 批 / 31）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

接口响应里有大量重复查询，我们想先在本机做一层缓存把热点读挡住。请从零实现一个进程内本地缓存组件，仓库里目前只有一个空的 Maven 工程（pom.xml 声明了 JUnit 5 与 AssertJ）。要求：1) 核心数据结构自行实现，**不允许依赖 Caffeine、Guava Cache、Ehcache 等现成缓存库**；2) 支持容量上限与两种淘汰策略（LRU、LFU），策略可通过配置切换；3) 支持两种过期语义：写入后存活时间（TTL）与访问后存活时间（TTI），已过期条目不得被返回；4) 并发安全：多线程并发读写不得出现异常或数据错乱，需提供并发读写测试；5) 统计信息：命中数、未命中数、淘汰数、过期淘汰数，并提供快照接口；6) 淘汰回调：条目被淘汰或过期时通知调用方（用于资源清理等），回调抛异常不得影响缓存主流程；7) `mvn -q verify` 一条命令跑通，测试需覆盖两种策略的淘汰顺序、两类过期语义、并发读写、统计准确性、回调异常隔离；8) README 说明数据结构选择、并发方案与已知限制。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. ./mvnw -q verify 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

---

# 实现说明：进程内本地缓存 `com.example.gsb.cache`

一个零外部依赖（仅 JDK）的有界、线程安全本地缓存，核心数据结构全部自行实现。

## 快速上手

```java
LocalCache<String, byte[]> cache = LocalCache.<String, byte[]>newBuilder()
        .maximumSize(10_000)
        .evictionStrategy(EvictionStrategy.LFU)      // 或 LRU（默认）
        .expireAfterWrite(Duration.ofMinutes(10))   // TTL
        .expireAfterAccess(Duration.ofMinutes(2))   // TTI，可只配一个或两个都配
        .removalListener(n -> releaseResource(n.getValue())) // 淘汰/过期回调
        .build();

cache.put("k", resource);
byte[] v = cache.get("k");                          // 命中返回值；不存在/已过期返回 null
byte[] loaded = cache.get("k", key -> loadFromDb(key)); // miss 时加载并回填
cache.invalidate("k");
StatsSnapshot stats = cache.statsSnapshot();        // 命中/未命中/淘汰/过期计数快照
```

## 包结构

| 类型 | 职责 |
|------|------|
| `LocalCache` | 缓存门面：Builder 配置、读写、淘汰、过期、统计、回调派发 |
| `CacheEntry` | 条目：key/value、写入时间、最近访问时间、频次，以及链表前后指针 |
| `EntryDeque` | 手写的侵入式双向链表（head=最新，tail=最冷），所有操作 O(1) |
| `EvictionTracker` | 淘汰索引接口：`add / recordAccess / remove / evict` |
| `LruTracker` | LRU：单条双向链表，tail 即最久未使用 |
| `LfuTracker` | LFU：按访问频次分桶（每桶一条双向链表）+ `minFrequency` 指针 |
| `RemovalListener` / `RemovalNotification` / `RemovalCause` | 淘汰回调（`SIZE` / `EXPIRED` / `REPLACED` / `EXPLICIT`） |
| `StatsSnapshot` | 不可变统计快照（record）：命中、未命中、容量淘汰、过期淘汰 |
| `Ticker` | 纳秒时钟抽象，生产用 `System.nanoTime()`，测试可注入手动时钟 |

## 数据结构选择

- **主存储**：`HashMap<K, CacheEntry>`，get/put 期望 O(1)。
- **LRU**：经典「HashMap + 双向链表」。链表节点直接内嵌在 `CacheEntry` 中
  （侵入式链表，无额外 `Node` 包装对象）；每次访问 O(1) 摘除并移到链表头，
  淘汰取链表尾。
- **LFU**：经典 O(1) LFU 的**频次分桶**方案——`HashMap<频次, EntryDeque>`，
  新条目进入频次 1 桶；访问时从桶 `f` 摘除、加入桶 `f+1`；额外维护
  `minFrequency` 指向当前最低非空桶，淘汰即取该桶桶尾。
  - 选受害者 O(1)；访问迁移 O(1) 摊还（最低桶耗尽时向上扫描到下一个非空桶，
    频次值从 1 连续递增，实际扫描极短）。
  - **频次相同按桶内 LRU 决胜**（同桶越靠尾越久没被访问），淘汰顺序确定、可预测。
- **过期**：不使用定时线程，采用惰性判定。条目同时记录 `writeNanos` 与
  `accessNanos`；get/containsKey 时按 `now - writeNanos >= TTL` 或
  `now - accessNanos >= TTI` 判定。容量不足时先全量清扫过期条目，再做
  SIZE 淘汰，因此“过期”优先于“容量淘汰”被记账和回调；另提供 `cleanUp()`
  主动清扫。
- **时间源**：基于 `System.nanoTime()`（单调、不受系统时钟回拨影响）；
  TTL/TTI 可单独或组合配置，组合时任一条件先到即过期。

## 并发方案

- 一把 `ReentrantLock` 保护 `HashMap` 与两个淘汰索引的全部读写，临界区内只做
  纯内存操作，保证多线程并发读写不丢更新、不破坏链表/分桶结构、容量不越界。
  读操作同样在锁内更新访问元数据（LRU 序、LFU 频次、TTI 时间），避免读写竞争。
- `CacheEntry.value` 为 `volatile`，使值引用对所有线程安全发布。
- 统计计数器在锁内用普通 `long` 累加；`statsSnapshot()` 在锁内复制出不可变
  `record`，快照之间互不影响。
- **回调在锁外派发**：临界区内先把待通知条目收集到本地列表，解锁后逐个回调。
  回调可以安全地反向调用缓存（不会自锁）；回调抛出的任何 `RuntimeException`/`Error`
  都会被吞掉并继续派发其余通知与主流程（并发压测中回调按 1/10 概率抛异常验证）。
- 不返回过期条目：发现过期即在锁内摘除、记一次 miss 与一次 expiration，再返回 null。

## 统计口径

| 计数器 | 含义 |
|--------|------|
| `hitCount` | get 命中且未过期的次数（回填式 `get(k, loader)` 命中也算） |
| `missCount` | get 未命中（不存在、或命中但已过期被惰性清除）次数 |
| `evictionCount` | 因容量超限被 SIZE 淘汰的条目数（不含过期、替换、显式删除） |
| `expirationCount` | 因 TTL/TTI 过期被清除的条目数（惰性清除、插入前清扫、`cleanUp()` 均计入） |

`StatsSnapshot` 另提供 `requestCount()`（=hit+miss）与 `hitRate()`。
覆盖写同 key 产生 `REPLACED` 回调，但不计淘汰、不计命中/未命中。

## 测试覆盖（`mvn -q verify` 一条命令）

- `LruEvictionTest`：LRU 淘汰顺序、访问刷新热度、覆盖写不触发淘汰。
- `LfuEvictionTest`：按频次淘汰、同频按 LRU 决胜、覆盖写重置频次。
- `ExpirationTest`：TTL 不随读续期、TTI 随访问滑动、TTL+TTI 组合、覆盖写
  重置 TTL、容量不足时优先清扫过期项、`cleanUp()`。
- `ConcurrentAccessTest`：8 线程 × 2 万次 混合 get/put/invalidate/cleanUp，
  LRU 与 LFU 各跑一遍（参数化），断言无线程异常、容量不越界、
  hit+miss 与实际 get 次数完全一致；另有 loader 回填的并发测试。
- `CacheStatsTest`：计数准确性、过期读同时计 miss+expiration、快照独立性。
- `RemovalListenerTest`：四种回调原因、回调键值正确、回调在容量淘汰与过期
  场景下抛异常均不影响主流程、`invalidateAll` 逐条通知。

## 已知限制

1. **单锁粒度**：所有读写共用一把锁，超高并发下读也会串行化。热点场景若
   需要无锁读，可改为 `ConcurrentHashMap` + CAS/分段索引，但实现复杂度显著上升。
2. **无后台过期线程**：过期条目在被访问、后续 put 需要空间或调用 `cleanUp()`
   时才真正释放；一个不再被访问的过期 key 可能短暂滞留内存（不影响正确性，
   过期值永远不会被返回）。
3. **LFU 频次不衰减**：历史热点可能长期占据高频桶、压制新 key（经典 LFU 的
   “老化”问题）。当前没有计数衰减/窗口重置，需要抗扫描污染时应选用 LRU 或
   后续扩展 W-TinyLFU。
4. **miss 回填不去重**：`get(key, loader)` 对并发同 key miss 不做
   single-flight，多个线程可能同时执行 loader，最后写入者生效。
5. **不持久化、不分布式、不统计条目内存字节数**：容量按条目条数计；
   `null` key/value 不被接受（value 为 null 会与“未命中”语义冲突）。
6. **长整型计数理论回绕**：命中率等指标在约 `Long.MAX_VALUE` 次请求后回绕，
   常规进程生命周期内不会发生。
