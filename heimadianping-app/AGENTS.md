# Repository Guidelines
一切的回答使用中文简体

## 项目结构与模块组织
核心业务位于 `heimadianping-app/src/main/java/com/zwz5`，按 `controller`、`service`、`mapper`、`pojo`、`config`、`utils` 分层；数据映射 XML 与配置放在 `src/main/resources/mapper` 与 `application.yaml`。Redis/MySQL/Nginx 的容器配置存放于仓库根目录下的 `docker-compose.yml`、`redis/`、`mysql/`、`nginx/`。实验性 Redis 示例位于 `demo/`，如需验证请避免污染正式模块。

## 技术栈与架构
后端基于 Spring Boot 3.5 与 Java 17，REST 层使用 `spring-boot-starter-web` 提供 JSON API，结合 `spring-boot-starter-actuator` 暴露运行指标。持久化由 MyBatis-Plus 3.5.6 驱动，`mybatis-spring` 3.0.3 负责与 Spring 框架集成，数据库连接使用 `mysql-connector-j`，建议通过 Mapper XML 管理 SQL 以便复用缓存配置。缓存与分布式锁依赖 `spring-boot-starter-data-redis`，连接池采用 `commons-pool2`，若需要自定义序列化请在 `config` 层扩展 `RedisTemplate`。开发阶段可启用 `spring-boot-devtools` 与 Lombok 注解处理器提升迭代效率。整体架构遵循 Controller-Service-Mapper 分层：Controller 接收请求并统一返回 `Result`，Service 负责业务流程、缓存预热与降级策略，Mapper 通过 XML/注解访问 MySQL，Redis 处理热点数据、缓存；必要时可在 `utils` 中封装工具类。

## AI 快速理解项目
- 这是一个“黑马点评”风格的单体后端，当前最完整的链路是：用户验证码登录、店铺缓存、优惠券秒杀。
- 登录鉴权依赖 Redis Token 会话：`RefreshTokenInterceptor` 负责从 `authorization` 读取 token、恢复 `UserDTO`、刷新 TTL；`LoginInterceptor` 负责登录校验；线程上下文使用 `UserHolder`。
- 店铺查询核心在 `ShopServiceImpl`，默认走 `RedisCacheClient#queryWithLogicalExpire`；代码同时保留了缓存穿透和互斥锁重建两套方案。店铺更新后会删缓存，并做异步延迟双删。
- 秒杀链路核心在 `VoucherOrderServiceRedissonImpl`，默认方案是 `Lua + Redisson + 阻塞队列 + AOP 事务代理`；`VoucherOrderServiceSetNxLuaImpl` 是另一套 `SetNX` 方案。
- Redis 在本项目里不仅是缓存，还承担登录态、分布式锁、Lua 预校验、全局 ID 生成等职责；看到业务改动时，先想 Redis 与 DB 是否需要一起调整。
- 当前社区能力还不完整：`BlogServiceImpl`、`FollowServiceImpl` 基本是骨架，`UserController#logout`、`UserController#me` 仍未完成。

## AI 安全修改守则
- 先定位真实入口再改：接口先看 `controller`，业务先看 `service.impl`，缓存/锁先看 `common/cache`、`common/lock`、`constants`、`lua/`。
- 修改店铺、优惠券、登录相关逻辑时，必须同时检查 Redis key、TTL、缓存失效、并发安全、事务边界，不要只改数据库代码。
- Controller 层保持薄，统一返回 `Result`；缓存、锁、回源、异步重建、秒杀扣库存等逻辑应留在 Service 或公共组件。
- 新增 Redis 相关逻辑时，优先复用 `RedisConstants`、`RedisCacheClient`、`RedisIdWorker`、`UserHolder`，不要在业务代码里散落硬编码 key。
- 涉及异步逻辑优先复用 `AsyncConfig` 中的 `cacheOpsExecutor`，避免直接落到公共线程池。
- 涉及秒杀下单时，不要破坏“一人一单”、库存扣减原子性和 AOP 代理事务调用；不要随意把异步队列改成普通同步串行逻辑。
- 涉及缓存更新时，不要遗漏空值缓存、TTL 抖动、逻辑过期或互斥锁兼容性；不要只更新 DB 不处理缓存。
- 统一沿用现有异常处理方式：业务异常优先交给 `WebExceptionAdvice`，不要把底层异常直接透传给前端。
- 仓库可能有用户未提交改动，除非用户明确要求，不要覆盖、回退或顺手清理与当前任务无关的修改。

## 构建、测试与本地运行
- `./mvnw clean package`：构建可执行 fat jar，输出到 `heimadianping-app/target/`。
- `./mvnw spring-boot:run`：依据默认配置启动 Spring Boot API。
- `./mvnw test`：执行 JUnit 5 测试套件。
如需依赖内置 Redis/MySQL，请先 `docker-compose up -d`。调试完成后使用 `docker-compose down` 清理资源；需要切换配置时可追加 `--spring.profiles.active=local` 并在日志中确认端口映射。版本冲突或依赖缺失时，可通过 `./mvnw dependency:tree` 排查并同步更新。

## 编码风格与命名约定
遵循 Java 标准四空格缩进；类名使用 PascalCase（例：`ShopController`），方法与字段采用 camelCase。REST 接口统一返回 `Result`；DTO、VO 各在对应包中维护，共享常量置于 `SystemConstants`。编写 MyBatis SQL 时保持 XML 中的 `<select id="findById">` 与接口方法命名一致，文件放在 `src/main/resources/mapper`。

## 测试规范
测试类位于 `src/test/java/com/zwz5`，命名以 `*Tests` 结尾。常规集成测试使用 `@SpringBootTest`，针对缓存或并发逻辑可使用切片注解（如 `@DataRedisTest`）与自定义数据初始化脚本，必要时模拟 Redis 缓存击穿与缓存雪崩场景。建议覆盖缓存命中、数据库回源、锁竞争等关键路径，提交前务必确保 `./mvnw test` 全量通过。

## 提交与 Pull Request 约定
Commit 信息简洁聚焦，推荐双语短祈使句，如 `优化店铺缓存降级策略`，单次提交聚焦单一问题，避免功能与重构混杂。PR 描述应说明改动范围、受影响模块（例：controller、service、SQL）、关联 Issue/文档链接

## 环境与配置提示
本地个性化配置放在 `application-local.yaml`，严禁提交敏感凭证。默认 Redis/MySQL 端口与 `docker-compose` 保持一致，若调整需同步 `.env` 与服务依赖。测试前可使用 `redis/redis-cli.sh` 或 `mysql/init/` 下脚本清理数据，保证缓存、数据源与负载均衡配置同步；部署前确认 `application.yaml` 中的资源连接、监控端点及限流参数符合环境约束，并在多节点场景下验证 Redis 与 MySQL 权限。

## 日志与运维提示
应用默认使用 Spring Boot Logging 输出，请在 `application.yaml` 中按环境调整日志级别；定位缓存或数据库瓶颈时，可结合 Actuator `/actuator/metrics` 与 Redis 命令 `info` 交叉验证。发布前建议在预发环境记录关键请求的 QPS、响应时间与命中率，确保缓存策略达标后再扩展至生产。
