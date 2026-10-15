# Gravitino 联邦元数据设计方案

> 目标：让一个 Gravitino 实例能够透明访问另一个（远端）Gravitino 实例的元数据，
> 实现跨区域 / 跨云的统一元数据视图。方案分两个层次：
> **Catalog 级联邦**（连接器插件）与 **Metalake 级联邦**（server 层装饰器）。

---

## 1. 背景与目标

### 1.1 问题

Apache Gravitino 1.3.0 的地理分布支持仅限于 Iceberg REST Catalog (IRC) 代理，无法实现 Gravitino 实例间的完整元数据联邦。用户需要：

- 跨区域、跨云部署多个 Gravitino 实例；
- 本地实例能透明访问远端实例的 **所有类型** 元数据（不限于 Iceberg）；
- 层级结构对齐，上层引擎（Trino/Spark/Flink）体验一致；
- 建好本地 metalake 后，**查询接口直接反映远端 catalog 列表**，无需手工逐个建代理。

### 1.2 目标

提供三层能力，覆盖从「代理单个远端 catalog」到「透明镜像整个远端 metalake」：

1. `gravitino` catalog provider —— 封装 Gravitino Java REST Client，把本地 catalog 的 schema/table 操作透明转发到远端。
2. Metalake 级 **物化同步** —— 一次性 / 定时把远端所有 catalog 批量落成本地代理。
3. Metalake 级 **透明实时联邦** —— 建好联邦 metalake 后，list/load catalog 时自动增量镜像远端，查询接口实时反映远端。

### 1.3 设计原则

- **连接器不改 core**：`gravitino` provider 作为 `catalogs-contrib` 插件，通过 SPI 加载。
- **透明联邦改 server、不改 core**：metalake 级实时联邦以 `CatalogDispatcher` 装饰器形式落在 `server` 模块，不侵入 core 的 `CatalogManager` / 存储层。
- **层级对齐**：本地 catalog = 远端 catalog，本地 schema = 远端 schema。
- **联邦 metalake 独立**：一个联邦 metalake 只承载远端镜像内容，不与其它本地 catalog 混合。
- **子树复用**：镜像出的是真实本地代理实体，schema/table 全部走原生 dispatcher + `gravitino` 连接器，无需为子树单独改核心。

---

## 2. 架构设计

### 2.1 三种联邦模式

| 模式 | 粒度 | 落库 | 触发方式 | 实时性 | 改动范围 |
|---|---|---|---|---|---|
| **A. Catalog 级联邦** | 单个远端 catalog | 是（1 条 catalog 实体） | 手动 `POST .../catalogs` | 手动 | 仅 contrib 插件 |
| **B. Metalake 级 · 物化同步** | 整个远端 metalake | 是（N 条代理 catalog） | CLI / 定时任务 | 快照，需再同步 | 仅 contrib 插件 |
| **C. Metalake 级 · 透明实时** | 整个远端 metalake | 是（自动增量维护） | 查询 list/load 时自动 | 准实时（TTL 刷新） | contrib 插件 + server 装饰器 |

模式 A/B/C 复用同一个 `gravitino` 连接器（负责 schema/table 转发）；差别只在「代理 catalog 由谁、何时创建」。

### 2.2 Catalog 级转发拓扑（模式 A/B/C 的子树基础）

```
┌─────────────────────────────────────────────────────────────────┐
│                    本地 Gravitino (Region A)                      │
│  metalake: local_ml                                              │
│    ├─ catalog: mysql_prod (jdbc-mysql)        ← 本地原生 catalog │
│    ├─ catalog: region_b_catalog_1 (gravitino) ──┐                │
│    └─ catalog: region_b_catalog_2 (gravitino) ──┤                │
│  GravitinoCatalogOperations                     │                │
│    └─ GravitinoClient ──────────────────────────┼──────┐         │
└─────────────────────────────────────────────────┼──────┼─────────┘
                                          REST API│      │
                                                  ▼      ▼
┌─────────────────────────────────────────────────────────────────┐
│                    远端 Gravitino (Region B)                      │
│  metalake: region_b_ml                                           │
│    ├─ catalog: catalog_1 (jdbc-mysql) → schema db1 → table orders│
│    └─ catalog: catalog_2 (lakehouse-iceberg) → schema analytics  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 Metalake 级透明联邦拓扑（模式 C）

```
                     HTTP: GET /api/metalakes/{federated_ml}/catalogs
   client ───────────────────────────────────────────────────────────┐
                                                                      ▼
   ┌────────────────────────── 本地 Gravitino Server ───────────────────────────┐
   │  REST: CatalogOperations                                                    │
   │        │  (bind 时被包裹)                                                    │
   │        ▼                                                                    │
   │  FederatedCatalogDispatcher  ← 新增装饰器 (server 模块)                      │
   │    1. metalakeDispatcher.loadMetalake → 读 federation.* 属性                │
   │    2. 若 federation.type=gravitino：                                         │
   │         - TTL 判断是否需刷新                                                 │
   │         - GravitinoClient 连远端 listCatalogsInfo()                         │
   │         - diff：新增→delegate.createCatalog(provider=gravitino)             │
   │                 消失→delegate.dropCatalog(force)                            │
   │    3. delegate.listCatalogsInfo() → 返回镜像后的本地代理列表                 │
   │        │  (写操作在联邦 metalake 下直接拒绝)                                 │
   │        ▼                                                                    │
   │  CatalogManager (core, 未改) → EntityStore                                  │
   └─────────────────────────────────────────────────────────────────────────┘
                       │ 远端枚举                         ▲ schema/table 子树
                       ▼                                  │ 走原生 dispatcher
   ┌──────────── 远端 Gravitino (region_b_ml) ───────────┐│  + gravitino 连接器
   │  catalog_1, catalog_2, ...                          │└───────────────────
   └─────────────────────────────────────────────────────┘
```

**关键取舍**：schema/table 查询走 `SchemaOperationDispatcher`/`TableOperationDispatcher` → `CatalogManager.loadCatalogAndWrap` → `store.get`，**不经过** `CatalogDispatcher`。因此代理 catalog 必须**物化到本地 store**，子树才能被原生链路解析——这也是模式 C 不改 core 的前提。

---

## 3. 模块结构

```
catalogs-contrib/gravitino-catalog/                     # 连接器插件（模式 A/B/C 子树）
├── build.gradle.kts
├── DESIGN.md
├── scripts/
│   └── federation-metalake.sh                          # 模式 B：建 metalake + 同步脚本
└── src/main/
    ├── java/org/apache/gravitino/catalog/gravitino/
    │   ├── GravitinoCatalog.java                       # CatalogProvider SPI 入口
    │   ├── GravitinoCatalogOperations.java             # 核心：schema/table 请求转发引擎
    │   ├── GravitinoCatalogPropertiesMetadata.java     # catalog 属性定义与校验
    │   ├── GravitinoEntityPropertiesMetadata.java      # 宽松 schema/table 属性元数据
    │   ├── GravitinoFederationService.java             # 模式 B：批量同步服务
    │   ├── FederationSyncCli.java                      # 模式 B：命令行触发同步
    │   ├── GravitinoSchema.java / GravitinoTable.java / GravitinoColumn.java
    └── resources/
        ├── META-INF/services/org.apache.gravitino.CatalogProvider
        └── gravitino.conf

server/src/main/java/org/apache/gravitino/server/web/federation/   # 模式 C：透明联邦
├── FederationConstants.java                            # federation.* / gravitino.* 键
└── FederatedCatalogDispatcher.java                     # CatalogDispatcher 装饰器
server/src/main/java/org/apache/gravitino/server/GravitinoServer.java  # bind 处包裹装饰器
server/build.gradle.kts                                 # +implementation(:clients:client-java)
```

---

## 4. 核心实现

### 4.1 Catalog Provider 注册

```java
public class GravitinoCatalog extends BaseCatalog<GravitinoCatalog> {
  @Override public String shortName() { return "gravitino"; }
  @Override protected CatalogOperations newOps(Map<String, String> config) {
    return new GravitinoCatalogOperations();
  }
  @Override public PropertiesMetadata catalogPropertiesMetadata() { return CATALOG_PROPERTIES_META; }
  @Override public PropertiesMetadata schemaPropertiesMetadata()  { return ENTITY_PROPERTIES_META; }
  @Override public PropertiesMetadata tablePropertiesMetadata()   { return ENTITY_PROPERTIES_META; }
}
```

> `schemaPropertiesMetadata()` / `tablePropertiesMetadata()` **必须重写**：`BaseCatalog` 默认抛
> `UnsupportedOperationException`，否则策略/加载链路（`getHiddenPropertyNames`）会失败。
> 联邦代理透传远端任意属性，故用宽松的 `GravitinoEntityPropertiesMetadata`（`specificPropertyEntries()` 返回空）。

SPI 文件 `META-INF/services/org.apache.gravitino.CatalogProvider`：
```
org.apache.gravitino.catalog.gravitino.GravitinoCatalog
```

### 4.2 请求转发引擎 (GravitinoCatalogOperations)

核心职责：桥接**服务端 connector 接口**与**客户端 API 接口**的差异。

```
服务端接口 (NameIdentifier-based)          客户端接口 (String-based)
─────────────────────────────────          ──────────────────────────
connector.SupportsSchemas                  api.SupportsSchemas
  listSchemas(Namespace)                     listSchemas() → String[]
  loadSchema(NameIdentifier)                 loadSchema(String)
  createSchema(NameIdentifier, ...)          createSchema(String, ...)

rel.TableCatalog (两端一致)
  listTables(Namespace) / loadTable(NameIdentifier)
```

**Namespace 转换：**
```java
// 本地 [metalake, catalog, schema] → 远端客户端 [schema]
private Namespace toRemoteSchemaNamespace(Namespace ns) {
  if (ns.length() <= 2) return Namespace.empty();
  return Namespace.of(Arrays.copyOfRange(ns.levels(), 2, ns.length()));
}
// 本地 table [metalake, catalog, schema, table] → 远端 [schema, table]
private NameIdentifier toRemoteTableIdentifier(NameIdentifier t) {
  return NameIdentifier.of(toRemoteSchemaNamespace(t.namespace()), t.name());
}
```

**Audit 转换：** 远端返回的 `AuditDTO`（API 层 `Audit`）需转成 core 层 `AuditInfo` 才能塞进实体 Builder：
```java
private AuditInfo toAuditInfo(Audit audit) {
  if (audit == null) return AuditInfo.EMPTY;
  if (audit instanceof AuditInfo) return (AuditInfo) audit;
  return AuditInfo.builder().withCreator(audit.creator()).withCreateTime(audit.createTime())
      .withLastModifier(audit.lastModifier()).withLastModifiedTime(audit.lastModifiedTime()).build();
}
```

### 4.3 Catalog 属性（模式 A/B/C 代理 catalog 通用）

| 属性 | 必填 | 不可变 | 默认值 | 说明 |
|---|---|---|---|---|
| `gravitino.uri` | 是 | 是 | - | 远端 Gravitino 服务地址 |
| `gravitino.metalake` | 是 | 是 | - | 远端 metalake 名称 |
| `gravitino.catalog` | 否 | 否 | 本地 catalog 名 | 远端 catalog 名称 |
| `gravitino.auth.type` | 否 | 否 | `simple` | 认证类型: simple/basic |
| `gravitino.auth.username` | 否 | 否 | - | 认证用户名 |
| `gravitino.auth.password` | 否 | 否 | - | 认证密码（hidden） |
| `gravitino.cache.enabled` | 否 | 否 | `false` | 是否启用本地缓存（预留，未实现） |
| `gravitino.cache.ttl` | 否 | 否 | `300s` | 缓存 TTL（预留） |
| `gravitino.cache.max-size` | 否 | 否 | `1000` | 缓存最大条目（预留） |

### 4.4 Metalake 联邦属性（模式 B/C，写在本地 metalake 上）

| 属性 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `federation.type` | 是 | - | 固定 `gravitino`，标记为联邦 metalake |
| `federation.uri` | 是 | - | 远端 Gravitino 服务地址 |
| `federation.metalake` | 是 | - | 远端 metalake 名称 |
| `federation.auth.type` | 否 | `simple` | simple/basic |
| `federation.auth.username` | 否 | - | 用户名 |
| `federation.auth.password` | 否 | - | 密码 |
| `federation.refresh.interval` | 否 | `30` | 模式 C 镜像刷新最小间隔（秒） |

> 安全：`federation.auth.password` 会随 metalake 属性持久化，且 metalake 属性当前不隐藏。
> 生产建议用 simple+用户名，或接入外部密钥管理。

### 4.5 物化同步服务 (GravitinoFederationService) —— 模式 B

```java
public int syncCatalogs() {
  Catalog[] remote = remoteClient.listCatalogsInfo();          // 1. 枚举远端
  for (Catalog rc : remote) {
    if (!localCatalogExists(rc.name())) {                      // 2. 幂等：已存在则跳过
      localClient.createCatalog(rc.name(), rc.type(), "gravitino",
          "Federated proxy to " + remoteMetalake + "." + rc.name(),
          buildProxyProperties(rc.name()));                    // gravitino.uri/metalake/catalog/auth
    }
  }
}
// unsyncCatalogs(force)：删除本地 provider=gravitino 的代理
```

由 `FederationSyncCli`（`sync`/`unsync`）或 `scripts/federation-metalake.sh` 触发。

### 4.6 透明实时联邦 (FederatedCatalogDispatcher) —— 模式 C

`CatalogDispatcher` 装饰器，bind 时包裹真实 dispatcher（见 `GravitinoServer`）：

```java
bind(new FederatedCatalogDispatcher(
        gravitinoEnv.catalogDispatcher(), gravitinoEnv.metalakeDispatcher()))
    .to(CatalogDispatcher.class).ranked(1);
```

**读路径（federation-aware）：**
```java
public Catalog[] listCatalogsInfo(Namespace ns) {
  mirrorIfFederated(ns, /* force= */ false);   // TTL 内跳过远端往返
  return delegate.listCatalogsInfo(ns);         // 返回镜像后的本地代理
}
public Catalog loadCatalog(NameIdentifier ident) {
  if (federationContext(metalakeOf(ident)) != null && !delegate.catalogExists(ident)) {
    mirrorIfFederated(ident.namespace(), /* force= */ true);  // 缺失即强制刷新，按需可见
  }
  return delegate.loadCatalog(ident);
}
```

**增量镜像算法（TTL + per-metalake 锁）：**
```
if 非联邦 metalake → return
now = epochSeconds
if !force 且 now - lastMirrorAt[ml] < refreshInterval → return   # 节流
synchronized (mirrorLocks[ml]):
   double-check TTL
   remote   = GravitinoClient(federation.uri, federation.metalake, auth)   # try-with-resources
   remotes  = remote.listCatalogsInfo()
   locals   = delegate.listCatalogsInfo(ns)
   for rc in remotes where rc.name ∉ locals:
        delegate.createCatalog(NameIdentifier.of(ns, rc.name), rc.type, "gravitino", comment, proxyProps(rc.name))
   for lc in locals where lc.provider == "gravitino" 且 lc.name ∉ remotes:
        delegate.dropCatalog(NameIdentifier.of(ns, lc.name), force=true)
   lastMirrorAt[ml] = now
   # 远端异常：捕获并 WARN 降级，返回本地已有镜像，不阻断查询
```

**写路径（拒绝）：** `createCatalog` / `alterCatalog` / `dropCatalog` / `testConnection` /
`enableCatalog` / `disableCatalog` 在联邦 metalake 下抛 `IllegalArgumentException`，
提示「联邦 metalake 为只读视图，请到远端执行写操作」。装饰器内部镜像调用的是 `delegate`，不受此限制。

**并发与性能：**
- `ConcurrentHashMap` 缓存 `lastMirrorAt` 与 per-metalake 锁，串行化同一 metalake 的镜像；
- 稳态（远端无变化）下 list 只做「远端枚举 + 本地枚举 + diff」，无写入；
- TTL 默认 30s，避免每次 list 都打远端；`loadCatalog` 未命中时强制刷新兜底。

---

## 5. API 使用示例

### 5.1 模式 A：Catalog 级联邦（代理单个远端 catalog）

```http
POST /api/metalakes/local_ml/catalogs
{
  "name": "remote_region_b", "type": "RELATIONAL", "provider": "gravitino",
  "comment": "联邦 Region B 远端 Gravitino",
  "properties": {
    "gravitino.uri": "http://region-b-gravitino:8090",
    "gravitino.metalake": "region_b_ml",
    "gravitino.catalog": "catalog_1",
    "gravitino.auth.type": "simple"
  }
}
```
查询远端表（透明转发）：
```
GET /api/metalakes/local_ml/catalogs/remote_region_b/schemas/db1/tables/orders
→ GET http://region-b-gravitino:8090/api/metalakes/region_b_ml/catalogs/catalog_1/schemas/db1/tables/orders
```

### 5.2 模式 C：Metalake 级透明实时联邦（推荐）

**Step 1 — 建联邦 metalake：**
```http
POST /api/metalakes
{
  "name": "region_b_federation",
  "comment": "Region B 实时视图",
  "properties": {
    "federation.type": "gravitino",
    "federation.uri": "http://region-b-gravitino:8090",
    "federation.metalake": "region_b_ml",
    "federation.auth.type": "simple",
    "federation.refresh.interval": "30"
  }
}
```

**Step 2 — 直接查，自动镜像远端（无需手工 sync）：**
```http
GET /api/metalakes/region_b_federation/catalogs
```
首次或 TTL 过期时，装饰器连远端枚举并把 catalog 增量镜像成本地代理，随后返回：
```json
{"code":0,"identifiers":[{"namespace":["region_b_federation"],"name":"catalog_1"},
                         {"namespace":["region_b_federation"],"name":"catalog_2"}]}
```

**Step 3 — 下钻 schema/table（走代理 → 远端）：**
```http
GET /api/metalakes/region_b_federation/catalogs/catalog_1/schemas
GET /api/metalakes/region_b_federation/catalogs/catalog_1/schemas/db1/tables/orders
```

**写操作被拒绝：**
```http
POST /api/metalakes/region_b_federation/catalogs   →  400
"Metalake 'region_b_federation' is a federated (read-only) view of remote metalake
 'region_b_ml'. Perform catalog write operations directly on the remote Gravitino instance."
```

### 5.3 模式 B：Metalake 级物化同步（CLI / 脚本）

```bash
# 一次性同步（快照）
java -cp "catalogs/gravitino/libs/*" \
  org.apache.gravitino.catalog.gravitino.FederationSyncCli sync \
  --metalake region_b_federation --local-uri http://local-gravitino:8090

# 或脚本：建 metalake + 同步
REMOTE_URI=http://region-b-gravitino:8090 REMOTE_METALAKE=region_b_ml \
LOCAL_METALAKE=region_b_federation \
  catalogs-contrib/gravitino-catalog/scripts/federation-metalake.sh sync

# 取消联邦
FederationSyncCli unsync --metalake region_b_federation --force
```

---

## 6. 远端 REST API 调用链

```
Catalog:  GET /api/metalakes/{ml}/catalogs      GET .../catalogs/{c}
Schema:   GET/POST/PUT/DELETE /api/metalakes/{ml}/catalogs/{c}/schemas[/{s}]
Table:    GET/POST/PUT/DELETE /api/metalakes/{ml}/catalogs/{c}/schemas/{s}/tables[/{t}]
```
模式 C 仅额外用到远端 `GET .../catalogs`（枚举）；schema/table 由代理 catalog 的连接器按需转发。

---

## 7. 构建与部署

### 7.1 构建
```bash
# 连接器插件（catalogs-contrib 默认不参与主构建）
./gradlew :catalogs-contrib:gravitino-catalog:build -PskipITs
# server（含模式 C 装饰器）
./gradlew :server:build -PskipITs
# 全量发行包（package → package-all）
./gradlew compileDistribution
```

### 7.2 产物与部署
```
distribution/package-all/catalogs/gravitino/
├── libs/   (gravitino-catalog.jar + client-java 依赖，排除 guava/slf4j/log4j)
└── conf/   (gravitino.conf)
distribution/package-all/libs/  (含更新后的 server jar)
```
- 根 `build.gradle.kts`：`copyCatalogLibAndConfigs` 已注册 `:catalogs-contrib:gravitino-catalog:copyLibAndConfig`；`copySubprojectLib` 已排除 `catalogs-contrib`（`it.parent?.name != "catalogs-contrib"`）。

### 7.3 启动
```bash
export GRAVITINO_HOME=$(pwd)/distribution/package-all
"$GRAVITINO_HOME/bin/gravitino.sh" run      # 或 start/stop/status
```
运行时 `CatalogManager` 按 provider 名从 `$GRAVITINO_HOME/catalogs/gravitino/libs` 用 `IsolatedClassLoader` 加载连接器；`GravitinoServer` 在 bind 时用 `FederatedCatalogDispatcher` 包裹 catalog dispatcher。

---

## 8. 与现有 IRC 联邦的对比

| 维度 | IRC 联邦（现有） | Gravitino 联邦（本方案） |
|---|---|---|
| 协议 | Iceberg REST Catalog | Gravitino REST API |
| 覆盖范围 | 仅 Iceberg 表 | 所有 catalog 类型 |
| 实现位置 | `iceberg-rest-server` | contrib 连接器 + server 装饰器 |
| 层级映射 | IRC1.warehouse → IRC2.catalog | local.catalog → remote.metalake.catalog |
| 凭证 | 重写 credential refresh | 透传远端凭证 |
| 改 core | 否 | 否（模式 C 改 server，不改 core） |

---

## 9. 三种模式选型建议

| 诉求 | 推荐模式 |
|---|---|
| 只想代理远端某一个 catalog | A |
| 想批量接管远端整个 metalake，可接受快照/定时刷新，且不愿动 server | B |
| 建好 metalake 后查询接口自动、准实时反映远端 catalog | C |
| 需要「绝对不落任何本地实体」的纯虚拟视图 | 需改 core `CatalogManager.loadCatalogInternal`（未实现，见 §11） |

---

## 10. 后续演进

| 阶段 | 内容 | 优先级 |
|---|---|---|
| P0 | RELATIONAL 联邦（schema+table）+ 模式 A/B/C | 当前实现 |
| P1 | 连接器本地缓存层（Guava Cache，TTL+max-size，落实 `gravitino.cache.*`） | 高 |
| P1 | FILESET 类型联邦 | 高 |
| P1 | 模式 C 远端 client 连接池 + 镜像结果缓存（减少 list 往返） | 高 |
| P2 | MESSAGING / MODEL 类型联邦 | 中 |
| P2 | 联邦链路健康检查、熔断与降级策略可配置化 | 中 |
| P2 | 纯虚拟视图（改 core，代理零落库） | 中 |
| P3 | OAuth2 / Kerberos 认证、终端用户身份透传 | 低 |
| P3 | 写操作转发（模式 C 由「拒绝」升级为「转发到远端」） | 低 |

---

## 11. 限制与注意事项

1. **网络依赖**：所有操作依赖本地到远端连通性，无离线降级（模式 C 远端异常时返回本地已有镜像并 WARN）。
2. **代理落库**：模式 B/C 会把远端 catalog 物化为本地代理实体（`provider=gravitino`）——这是不改 core 让 schema/table 生效的前提；联邦 metalake 专用，不与本地 catalog 合并。
3. **模式 C 写操作被拒**：联邦 metalake 为只读视图；写需去远端，或降级用模式 A 手工建可写代理。
4. **刷新延迟**：模式 C 受 `federation.refresh.interval`（默认 30s）节流，远端新增 catalog 最长延迟一个 TTL 可见；`loadCatalog` 未命中会强制刷新兜底。
5. **认证透传**：当前使用固定凭证，不支持终端用户身份透传；密码随 metalake/ catalog 属性持久化。
6. **类型兼容**：远端 column type 必须能在本地 Gravitino 类型系统表达。
7. **版本兼容**：GravitinoClient 校验远端 server 版本，client 版本不能高于 server。
8. **依赖边**：模式 C 引入 `server → clients:client-java`（client-java 的 test 本就依赖 server，main 无环）；若 Gradle 报循环依赖，退化为 core 内裸 HTTP 方案。
```
