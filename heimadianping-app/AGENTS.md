# Repository Guidelines
一切的回答使用中文简体

## 项目结构与模块组织
当前文件位于后端应用模块 `heimadianping-app/`，Git 仓库根目录在其父级。后端核心业务位于 `src/main/java/com/zwz5`，整体是 Spring Boot 单体应用，按下面的职责拆分：

- `HeimadianpingAppApplication.java`：应用启动入口。
- `controller/`：REST 接口入口，包括用户、店铺、店铺分类、博客、关注、优惠券、秒杀订单、上传等控制器；Controller 层应保持薄，统一返回 `Result`。
- `service/` 与 `service/impl/`：业务接口与实现，承载登录、店铺缓存、优惠券秒杀、关注、博客等流程；复杂的缓存、锁、事务、异步队列逻辑优先放在这里或公共组件中。
- `mapper/`：MyBatis-Plus Mapper 接口，负责数据库访问；复杂 SQL 可配合 `src/main/resources/mapper` 下的 XML。
- `pojo/entity/`：数据库实体，如 `User`、`Shop`、`Voucher`、`VoucherOrder`、`Blog`、`Follow` 等。
- `pojo/dto/`：接口传输对象，目前包含 `LoginFormDTO`、`UserDTO`。
- `common/result/`：统一响应与滚动分页结果对象。
- `common/cache/`：缓存访问封装，包括 `CacheClient`、`RedisCacheClient`、`RedissonCacheClient`。
- `common/lock/`：Redis 分布式锁抽象与实现。
- `common/redis/`：Redis 通用数据结构与全局 ID 工具。
- `common/utils/`：JSON、密码、随机值、正则校验、用户线程上下文等通用工具。
- `config/`：Spring MVC、MyBatis-Plus、Redisson、异步线程池、全局异常处理、自动填充等配置。
- `constants/`：Redis key/TTL、系统常量、秒杀结果码等常量定义。
- `exception/`：业务异常类型。
- `interceptor/`：登录校验与 Token 刷新拦截器。

资源文件位于 `src/main/resources`：

- `application.yaml`：应用配置，包含服务、数据源、Redis、MyBatis-Plus、Actuator 等配置。
- `mapper/VoucherMapper.xml`：优惠券相关 XML SQL 映射。
- `lua/seckill_script.lua`：秒杀库存与一人一单的 Redis Lua 预校验脚本。
- `lua/unlock_script.lua`：Redis 锁释放脚本。

测试位于 `src/test/java/com/zwz5`：

- `HeimadianpingAppApplicationTests.java`：Spring Boot 上下文测试。
- `common/redis/LoadShopDataTest.java`：店铺数据缓存预热/加载相关测试。
- `common/redis/RedisIdWorkerTest.java`：Redis 全局 ID 工具测试。
- `common/user/GenerateTokensForJmeterTest.java` 与 `tokens.txt`：压测 Token 生成与输出文件，注意不要把临时压测数据误当业务代码。

仓库父级还包含运行与验证资源：`docker-compose.yml` 统一编排本地依赖，`mysql/` 存放 MySQL 数据与 SQL 初始化资源，`redis/` 存放 Redis 配置与数据目录，`nginx/` 存放前端静态资源与 Nginx 配置，`jmeter/` 存放秒杀压测脚本，`demo/` 存放 Jedis 与 Spring Data Redis 示例。修改后端应用时通常只需在当前 `heimadianping-app/` 模块内操作；验证基础设施或压测时再进入父级对应目录，避免把示例工程或运行数据混入正式模块。

## 技术栈与架构
后端基于 Spring Boot 3.5 与 Java 17，REST 层使用 `spring-boot-starter-web` 提供 JSON API，结合 `spring-boot-starter-actuator` 暴露运行指标。持久化由 MyBatis-Plus 3.5.6 驱动，`mybatis-spring` 3.0.3 负责与 Spring 框架集成，数据库连接使用 `mysql-connector-j`，建议通过 Mapper XML 管理 SQL 以便复用缓存配置。缓存与分布式锁依赖 `spring-boot-starter-data-redis`，连接池采用 `commons-pool2`，若需要自定义序列化请在 `config` 层扩展 `RedisTemplate`。开发阶段可启用 `spring-boot-devtools` 与 Lombok 注解处理器提升迭代效率。整体架构遵循 Controller-Service-Mapper 分层：Controller 接收请求并统一返回 `Result`，Service 负责业务流程、缓存预热与降级策略，Mapper 通过 XML/注解访问 MySQL，Redis 处理热点数据、缓存；必要时可在 `utils` 中封装工具类。

## AI 快速理解项目
- 这是一个“黑马点评”风格的单体后端，当前最完整的链路是：用户验证码登录、店铺缓存、优惠券秒杀。
- 登录鉴权依赖 Redis Token 会话：`RefreshTokenInterceptor` 负责从 `authorization` 读取 token、恢复 `UserDTO`、刷新 TTL；`LoginInterceptor` 负责登录校验；线程上下文使用 `UserHolder`。
- 店铺查询核心在 `ShopServiceImpl`，默认走 `RedisCacheClient#queryWithLogicalExpire`；代码同时保留了缓存穿透和互斥锁重建两套方案。店铺更新后会删缓存，并做异步延迟双删。
- 秒杀链路核心在 `VoucherOrderServiceRedissonImpl`，默认方案是 `Lua + Redisson + 阻塞队列 + AOP 事务代理`；`VoucherOrderServiceSetNxLuaImpl` 是另一套 `SetNX` 方案。
- Redis 在本项目里不仅是缓存，还承担登录态、分布式锁、Lua 预校验、全局 ID 生成等职责；看到业务改动时，先想 Redis 与 DB 是否需要一起调整。
- 当前社区能力还不完整：`BlogServiceImpl`、`FollowServiceImpl` ，`UserController#logout`、`UserController#me` 已完成初版实现。

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

