package com.heima.redis.config;

import io.lettuce.core.ReadFrom;
import io.lettuce.core.internal.HostAndPort;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolvers;
import io.lettuce.core.resource.MappingSocketAddressResolver;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableConfigurationProperties(RedisConfig.RedisAddressMappingProperties.class)
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory){
        // 创建RedisTemplate对象
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        // 设置连接工厂
        template.setConnectionFactory(connectionFactory);
        // 创建JSON序列化工具
        GenericJackson2JsonRedisSerializer jsonRedisSerializer = new GenericJackson2JsonRedisSerializer();
        // 设置Key的序列化
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        // 设置Value的序列化
        template.setValueSerializer(jsonRedisSerializer);
        template.setHashValueSerializer(jsonRedisSerializer);
        // 返回
        return template;
    }

    @Bean
    public ClientResources redisClientResources(RedisAddressMappingProperties properties) {
        Map<HostAndPort, HostAndPort> addressMappings = properties.getAddressMappings().stream()
                .collect(Collectors.toMap(
                        mapping -> HostAndPort.parseCompat(mapping.getFrom()),
                        mapping -> HostAndPort.parseCompat(mapping.getTo()),
                        (left, right) -> right
                ));

        return DefaultClientResources.builder()
                // Sentinel 返回 Docker 内网 Redis 地址时，在这里映射成本机可访问地址。
                .socketAddressResolver(MappingSocketAddressResolver.create(DnsResolvers.UNRESOLVED,
                        hostAndPort -> addressMappings.getOrDefault(hostAndPort, hostAndPort)))
                .build();
    }

    @Bean
    public LettuceClientConfigurationBuilderCustomizer clientConfigurationBuilderCustomizer(ClientResources redisClientResources) {
        return clientConfigurationBuilder -> clientConfigurationBuilder
                .clientResources(redisClientResources)
                // 读写分离：写命令仍发往 master，读命令优先发往 replica；replica 不可用时回退到 master。
                .readFrom(ReadFrom.REPLICA_PREFERRED);
    }

    @ConfigurationProperties(prefix = "app.redis")
    public static class RedisAddressMappingProperties {
        private List<AddressMapping> addressMappings = new ArrayList<>();

        public List<AddressMapping> getAddressMappings() {
            return addressMappings;
        }

        public void setAddressMappings(List<AddressMapping> addressMappings) {
            this.addressMappings = addressMappings;
        }
    }

    public static class AddressMapping {
        private String from;
        private String to;

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }
    }

}
