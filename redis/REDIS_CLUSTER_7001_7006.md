# Redis Cluster 7001-7006 练习环境

本文档对应仓库根目录 `/Users/zhaowenzhuo/Workspace/heimadianping-app` 下的 Docker Compose 配置，新增 6 个 Redis Cluster 节点：

- `redis-7001`
- `redis-7002`
- `redis-7003`
- `redis-7004`
- `redis-7005`
- `redis-7006`

目标架构是 3 主 3 从。当前配置保留原有 `6380-6382` 和 Sentinel 配置，不改动已有哨兵环境。

## 1. 创建目录

在仓库根目录执行：

```bash
cd /Users/zhaowenzhuo/Workspace/heimadianping-app

mkdir -p \
  redis/7001-data \
  redis/7002-data \
  redis/7003-data \
  redis/7004-data \
  redis/7005-data \
  redis/7006-data
```

为什么这么做：每个 Redis 节点都需要独立工作目录。`nodes.conf`、AOF、RDB 等运行时文件必须隔离，否则多个节点会互相覆盖集群元数据。

## 2. 编写 7001-7006 配置文件

文件已创建在：

- `redis/7001.conf`
- `redis/7002.conf`
- `redis/7003.conf`
- `redis/7004.conf`
- `redis/7005.conf`
- `redis/7006.conf`

以 `7001.conf` 为例：

```conf
port 7001
bind 0.0.0.0
protected-mode no
dir /data

cluster-enabled yes
cluster-config-file nodes.conf
cluster-node-timeout 5000

cluster-announce-ip 172.30.0.31
cluster-announce-hostname localhost
cluster-preferred-endpoint-type hostname
cluster-announce-port 7001
cluster-announce-bus-port 17001

appendonly yes
```

其余节点只需要替换端口和静态 IP：

| 节点 | 端口 | bus 端口 | Docker IP |
|---|---:|---:|---|
| redis-7001 | 7001 | 17001 | 172.30.0.31 |
| redis-7002 | 7002 | 17002 | 172.30.0.32 |
| redis-7003 | 7003 | 17003 | 172.30.0.33 |
| redis-7004 | 7004 | 17004 | 172.30.0.34 |
| redis-7005 | 7005 | 17005 | 172.30.0.35 |
| redis-7006 | 7006 | 17006 | 172.30.0.36 |

关键配置说明：

- `cluster-enabled yes`：开启 Cluster 模式。
- `cluster-config-file nodes.conf`：每个节点自己的集群元数据文件，必须放在独立 `/data` 目录。
- `cluster-node-timeout 5000`：节点失联 5 秒后进入疑似故障判断，学习环境便于观察；生产环境要结合网络抖动调大。
- `appendonly yes`：开启 AOF，练习重启恢复更直观；生产环境还要评估 fsync 策略和磁盘性能。
- `protected-mode no`：学习环境便于宿主机连接；生产环境不要裸奔，应开启 ACL/密码并限制网络。
- `cluster-announce-ip`：节点之间通信用 Docker 静态 IP。
- `cluster-announce-hostname localhost` 和 `cluster-preferred-endpoint-type hostname`：宿主机客户端收到 `MOVED` 时优先拿到 `localhost:700x`，适合本机练习。这个配置需要 Redis 7+。
- `cluster-announce-bus-port`：Cluster bus 端口，默认是 Redis 端口 + 10000。这里显式映射 `17001-17006`，便于学习和排查。

## 3. Docker Compose 新增片段

已追加到 `docker-compose.yml`。完整新增片段如下：

```yaml
  redis-7001:
    image: redis:7.2
    command: ["redis-server", "/usr/local/etc/redis/redis.conf"]
    networks:
      redis-sentinel-net:
        ipv4_address: 172.30.0.31
    volumes:
      - ./redis/7001-data:/data
      - ./redis/7001.conf:/usr/local/etc/redis/redis.conf:ro
    ports:
      - "7001:7001"
      - "17001:17001"
    restart: unless-stopped

  redis-7002:
    image: redis:7.2
    command: ["redis-server", "/usr/local/etc/redis/redis.conf"]
    networks:
      redis-sentinel-net:
        ipv4_address: 172.30.0.32
    volumes:
      - ./redis/7002-data:/data
      - ./redis/7002.conf:/usr/local/etc/redis/redis.conf:ro
    ports:
      - "7002:7002"
      - "17002:17002"
    restart: unless-stopped

  redis-7003:
    image: redis:7.2
    command: ["redis-server", "/usr/local/etc/redis/redis.conf"]
    networks:
      redis-sentinel-net:
        ipv4_address: 172.30.0.33
    volumes:
      - ./redis/7003-data:/data
      - ./redis/7003.conf:/usr/local/etc/redis/redis.conf:ro
    ports:
      - "7003:7003"
      - "17003:17003"
    restart: unless-stopped

  redis-7004:
    image: redis:7.2
    command: ["redis-server", "/usr/local/etc/redis/redis.conf"]
    networks:
      redis-sentinel-net:
        ipv4_address: 172.30.0.34
    volumes:
      - ./redis/7004-data:/data
      - ./redis/7004.conf:/usr/local/etc/redis/redis.conf:ro
    ports:
      - "7004:7004"
      - "17004:17004"
    restart: unless-stopped

  redis-7005:
    image: redis:7.2
    command: ["redis-server", "/usr/local/etc/redis/redis.conf"]
    networks:
      redis-sentinel-net:
        ipv4_address: 172.30.0.35
    volumes:
      - ./redis/7005-data:/data
      - ./redis/7005.conf:/usr/local/etc/redis/redis.conf:ro
    ports:
      - "7005:7005"
      - "17005:17005"
    restart: unless-stopped

  redis-7006:
    image: redis:7.2
    command: ["redis-server", "/usr/local/etc/redis/redis.conf"]
    networks:
      redis-sentinel-net:
        ipv4_address: 172.30.0.36
    volumes:
      - ./redis/7006-data:/data
      - ./redis/7006.conf:/usr/local/etc/redis/redis.conf:ro
    ports:
      - "7006:7006"
      - "17006:17006"
    restart: unless-stopped
```

网络沿用现有配置：

```yaml
networks:
  redis-sentinel-net:
    driver: bridge
    ipam:
      config:
        - subnet: 172.30.0.0/24
```

为什么用同一个 network：Cluster 节点之间需要互相连接 client port 和 cluster bus port。固定 IP 可以让 `cluster-announce-ip` 稳定，不受容器重建影响。

## 4. 启动容器

```bash
cd /Users/zhaowenzhuo/Workspace/heimadianping-app

docker compose up -d \
  redis-7001 \
  redis-7002 \
  redis-7003 \
  redis-7004 \
  redis-7005 \
  redis-7006
```

查看状态：

```bash
docker compose ps redis-7001 redis-7002 redis-7003 redis-7004 redis-7005 redis-7006
```

## 5. 创建 3 主 3 从集群

```bash
cd /Users/zhaowenzhuo/Workspace/heimadianping-app

docker compose exec -T redis-7001 redis-cli --cluster create \
  172.30.0.31:7001 \
  172.30.0.32:7002 \
  172.30.0.33:7003 \
  172.30.0.34:7004 \
  172.30.0.35:7005 \
  172.30.0.36:7006 \
  --cluster-replicas 1 \
  --cluster-yes
```

为什么用 Docker IP 创建：集群节点之间要通过容器网络互相握手和复制。不要用 `redis-7001` 这种 service name 给宿主机客户端使用，因为宿主机默认不能解析 Docker Compose service name。

## 6. 验证 cluster nodes

```bash
docker compose exec -T redis-7001 redis-cli -p 7001 cluster nodes
```

正常结果应看到 3 个 `master` 和 3 个 `slave`，并且槽位覆盖：

- `0-5460`
- `5461-10922`
- `10923-16383`

## 7. 验证 cluster info

```bash
docker compose exec -T redis-7001 redis-cli -p 7001 cluster info
```

关键字段：

```text
cluster_state:ok
cluster_slots_assigned:16384
cluster_slots_ok:16384
cluster_known_nodes:6
cluster_size:3
```

## 8. 测试 set/get

如果宿主机安装了 `redis-cli`：

```bash
redis-cli -c -p 7001 set alpha v1
redis-cli -c -p 7001 get alpha
```

`-c` 表示 cluster 模式客户端，会自动跟随 `MOVED` 重定向。

如果宿主机没有 `redis-cli`，可以在节点容器里测试当前槽位。`alpha` 的 slot 在 7001 上：

```bash
docker compose exec -T redis-7001 redis-cli -p 7001 set alpha v1
docker compose exec -T redis-7001 redis-cli -p 7001 get alpha
```

## 9. 测试 MOVED 重定向

不开 `-c`，访问一个不属于 7001 的 key：

```bash
docker compose exec -T redis-7001 redis-cli -p 7001 set foo v2
```

预期看到类似：

```text
MOVED 12182 localhost:7003
```

这说明 key 的 hash slot 不在当前节点，Redis 返回了负责该 slot 的节点地址。宿主机客户端应使用 `redis-cli -c` 或 Lettuce/Jedis Cluster 客户端自动处理。

## 10. 测试故障转移

先找 master：

```bash
docker compose exec -T redis-7002 redis-cli -p 7002 cluster nodes
```

停止一个 master，例如 7001：

```bash
docker compose stop redis-7001
```

等待超过 `cluster-node-timeout`，这里建议 10-15 秒：

```bash
sleep 15
docker compose exec -T redis-7002 redis-cli -p 7002 cluster nodes
```

正常现象：7001 被标记为 `fail`，它的 replica 7005 会晋升为 `master`，并接管原来的 `0-5460` 槽位。

恢复 7001：

```bash
docker compose start redis-7001
sleep 10
docker compose exec -T redis-7002 redis-cli -p 7002 cluster nodes
docker compose exec -T redis-7002 redis-cli -p 7002 cluster info
```

正常现象：7001 回来后通常会作为 7005 的 replica 加回集群，不会自动抢回 master。这是 Redis Cluster 的正常行为。

## 11. cluster-announce 配置选择

本环境使用：

```conf
cluster-announce-ip 172.30.0.31
cluster-announce-hostname localhost
cluster-preferred-endpoint-type hostname
cluster-announce-port 7001
cluster-announce-bus-port 17001
```

原因：

- Docker 内部节点通信需要稳定的容器 IP。
- 宿主机客户端需要收到可连接的地址，所以重定向返回 `localhost:700x`。
- Redis 6.2 不支持 `cluster-announce-hostname` 和 `cluster-preferred-endpoint-type`，所以 Cluster 节点使用 `redis:7.2`。

不推荐在宿主机客户端里使用 `redis-7001`：

- `redis-7001` 是 Docker Compose 网络内 DNS 名称。
- 宿主机上的 Java/Lettuce 默认解析不了这个 service name。
- 如果把它通告给客户端，宿主机客户端会在 `MOVED redis-7003:7003` 后连接失败。

如果必须使用 Redis 6.2，更适合的本地方案是把每个节点的 `cluster-announce-ip` 改为宿主机局域网 IP，例如 `192.168.x.x`，并确保容器也能访问这个 IP 的映射端口。但该 IP 会随网络变化，不如 Redis 7 的 hostname endpoint 方案方便。

## 12. Spring Boot / Lettuce 示例

宿主机运行应用时可使用：

```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - localhost:7001
          - localhost:7002
          - localhost:7003
          - localhost:7004
          - localhost:7005
          - localhost:7006
```

如果使用密码，所有节点必须配置一致的 `requirepass` 和 `masterauth`，客户端也要配置 password。

## 13. 常见问题排查

### CLUSTERDOWN

常见原因：

- 集群还没创建，只是启动了 6 个独立节点。
- 部分槽位没有分配。
- 少数 master 不可用，且没有可晋升 replica。

排查：

```bash
docker compose exec -T redis-7001 redis-cli -p 7001 cluster info
docker compose exec -T redis-7001 redis-cli -p 7001 cluster nodes
```

如果是新环境想重建，先停容器并清理 `redis/700*-data` 下的运行时文件，再重新建群。

### MOVED

`MOVED` 不是错误，而是 Redis Cluster 告诉客户端目标 slot 在另一个节点。

解决：

```bash
redis-cli -c -p 7001
```

Java 应使用 Lettuce/Jedis 的 Cluster 客户端，而不是普通单节点客户端。

### Waiting for the cluster to join

常见原因：

- 节点之间无法访问 client port 或 bus port。
- `cluster-announce-ip` 通告了错误地址。
- 端口映射缺少 `17001-17006`。
- 数据目录里残留旧的 `nodes.conf`。

排查：

```bash
docker compose logs redis-7001
docker compose exec -T redis-7001 redis-cli -p 7001 cluster nodes
```

必要时清理后重建：

```bash
docker compose down
rm -rf redis/7001-data/* redis/7002-data/* redis/7003-data/* redis/7004-data/* redis/7005-data/* redis/7006-data/*
docker compose up -d redis-7001 redis-7002 redis-7003 redis-7004 redis-7005 redis-7006
```

### 宿主机 Lettuce 连接不上

重点看 `MOVED` 或 `CLUSTER SLOTS` 返回的地址。如果返回 `172.30.0.x`，宿主机在 Docker Desktop 上通常连不上；如果返回 `redis-700x`，宿主机通常解析不了。

本环境期望返回 `localhost:700x`。验证：

```bash
docker compose exec -T redis-7001 redis-cli -p 7001 set foo v2
```

应看到：

```text
MOVED 12182 localhost:7003
```

### Docker IP / 127.0.0.1 / service name

- Docker IP：适合容器间通信，不适合 Docker Desktop 宿主机客户端。
- `127.0.0.1`：适合宿主机客户端，但如果直接作为 `cluster-announce-ip`，容器节点之间会误连自己。
- service name：适合 Compose 网络内部客户端，不适合宿主机客户端。
- 本地练习推荐：`cluster-announce-ip` 用 Docker 静态 IP，`cluster-announce-hostname` 用 `localhost`，并使用 Redis 7+。
