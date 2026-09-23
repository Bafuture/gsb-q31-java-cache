# 进程内本地缓存组件（`gsb-q31-java-cache`）

一个零第三方依赖、从零实现的 Java 进程内缓存：支持容量上限、LRU/LFU 淘汰、
TTL（写入后存活）与 TTI（访问后存活）过期、线程安全、统计快照与淘汰回调。

## 快速开始

```bash
mvn -q verify        # 或 ./mvnw -q verify，需要 JDK 17+
```

```java
Cache<String, byte[]> cache = LocalCache.create(
        CacheConfig.newBuilder()
                .capacity(10_000)
                .evictionPolicy(EvictionPolicy.LFU)
                .expireAfterWrite(Duration.ofMinutes(10)) // 默认 TTL
                .expireAfterAccess(Duration.ofMinutes(2)) // 默认 TTI
                .evictionListener((k, v, cause) -> release(v))
                .build());

cache.put("k", data);
Optional<byte[]> v = cache.get("k");
cache.put("k2", data, Duration.ofSeconds(30), null); // 单条目覆盖默认 TTL
CacheStats s = cache.statsSnapshot();                 // hits/misses/evictions/expiredEvictions
```

## 功能一览

- 容量上限：达到上限时按所选策略淘汰，缓存大小永远不超过 `capacity`。
- 两种淘汰策略（`EvictionPolicy`，构建时切换）：`LRU`、`LFU`（同频次内按 LRU 打破平手）。
- 两种过期语义，可单独或同时配置（同时配置时先到者先生效）：
  - TTL：自写入时刻起固定存活，读不续期；
  - TTI：自最近一次访问起存活，命中读会刷新；
  - 支持缓存级默认值，也支持 `put` 时按条目单独指定。
- 统计快照：命中数、未命中数、容量淘汰数、过期淘汰数、当前条目数、命中率。
- 淘汰回调：条目因 `EVICTED` / `EXPIRED` / `REMOVED` / `REPLACED` 离开时通知调用方。
- 过期清理：访问时惰性清理；写满时主动扫描过期条目腾位；也可手动 `cleanUp()`。

## 数据结构选择

核心代码位于 `src/main/java/com/example/gsb/cache/internal`，未使用任何缓存库，
也没有借用 `LinkedHashMap` 之类带顺序语义的集合。

- **主存储**：`HashMap<K, Node<K,V>>`，O(1) 定位条目。
- **条目节点 `Node`**：保存 value、写入时间、最近访问时间、TTL/TTI 时长、
  访问频次，以及 `prev/next` 双向链表指针。
- **LRU（`LruStrategy`）**：手写双向链表，插入/命中移到表头，淘汰取表尾，全部 O(1)。
- **LFU（`LfuStrategy`）**：经典「频次桶」方案——
  `HashMap<频次, 双向链表>` 加一个 `minFrequency` 指针。
  - 插入进入频次 1 的桶；命中时从旧桶摘除、频次 +1、挂到新桶表头；
  - 淘汰取 `minFrequency` 桶的表尾（频次最低且同频次中最久未访问，O(1)）；
  - 最低频桶变空时上移指针；显式删除导致最低频桶消失时重扫桶集合修正。
- **策略接口 `EvictionStrategy`**：`onInsert / onAccess / onRemove / evictionCandidate`，
  两种策略实现同一接口，`LocalCache` 按配置装配。

## 并发方案

- 缓存内部使用**单把 `ReentrantLock`** 保护 `HashMap` 与淘汰结构的所有读写。
  单锁的好处是 map 与 LRU/LFU 链表在任何瞬间都保持一致，实现简单且可证明正确；
  对「挡住热点读」的典型场景（读命中仅更新两个链表指针）开销可控。
- 计数（命中/未命中/淘汰/过期）也在锁内以普通 `long` 维护，`statsSnapshot()`
  在锁内拷贝，快照天然一致。
- **回调在锁外执行**：锁内只把待通知事件收集到局部列表，释放锁后逐个回调，
  因此慢回调不会阻塞其他线程，回调里重入缓存也不会自锁。
- 回调抛出的任何 `RuntimeException` / `Error` 都被捕获并吞掉（当前不打日志，
  见「已知限制」），不影响缓存主流程与后续回调。
- 时间来自可替换的 `Ticker`（默认 `System.nanoTime()`，单调时钟），
  测试注入 `FakeTicker` 可以确定性地推进时间。

## 测试覆盖（`mvn -q verify`，共 25 个用例）

- `LruEvictionTest`：LRU 淘汰顺序、读刷新 recency、替换不扩容、invalidate 释放容量。
- `LfuEvictionTest`：LFU 按频次淘汰、同频次 LRU 平手、热点存活、替换重置频次。
- `ExpirationTest`：TTL 读不续期、TTI 读续期、过期访问记 miss+过期、
  写满时扫描过期腾位、`cleanUp()`、单条目时长覆盖、TTL+TTI 组合先到先生效。
- `ConcurrentAccessTest`：8 线程 × 20000 次混合读写，分别覆盖 LRU 和
  LFU+TTI；断言无异常、容量不超限、每个 get 恰好计数一次、回调计数与统计一致。
- `CacheStatsTest`：命中/未命中/淘汰/过期计数与命中率。
- `EvictionListenerTest`：四种移除原因通知、回调持续抛异常时缓存正常、
  淘汰事件携带正确的 key/value。
- `CacheConfigTest`：非法容量与零时长被拒绝。

## 已知限制

- **单把全局锁**：所有读串行化，极端高并发（多核纯读 QPS 数十万以上）下吞吐
  不如分段锁/Caffeine 的无争用读设计；当前优先保证正确性与简单。
- **无后台过期线程**：过期是惰性 + 写满扫描 + 手动 `cleanUp()`。从不被访问的
  过期条目不会主动释放内存（也不会发回调），直到写满扫描或 `cleanUp()` 被调用。
- **写满时扫描过期为 O(n)**：仅在容量已满且需要插入新 key 时发生一次；
  纯容量淘汰路径仍是 O(1)。
- **LFU 无频次衰减**：历史高频 key 会长期保持优势（经典 LFU 的老化问题），
  替换（同 key 重新 put）会把频次重置为 1。
- **null 值处理**：value 允许为 null（语义上有效），因此 `get` 返回
  `Optional<V>` 而不是用 null 表示未命中；null key 不支持（依赖 HashMap 语义）。
- **回调异常被静默吞掉**：除了「不影响主流程」外没有日志/告警出口，
  需要观测时请在回调实现内部自行记录。
- **进程内组件**：不做持久化、不做多实例一致性；重启即丢失，不适合跨节点共享。
