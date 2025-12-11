package com.zwz5.common.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zwz5.constants.RedisConstants;
import com.zwz5.pojo.dto.UserDTO;
import com.zwz5.pojo.entity.User;
import com.zwz5.service.IUserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cglib.beans.BeanMap;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootTest
public class GenerateTokensForJmeterTest {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 批量给用户“登录”，并把 token 写入 txt 文件
     */
    @Test
    void generateTokensForAllUsers() throws IOException {
        // 1. 查询数据库中所有用户（如果你只想要前1000个，可以加 limit）
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.last("LIMIT 1000");

        List<User> users = userService.list(wrapper);
        log.info("总用户数：{}", users.size());

        // 2. 准备写入 token 的文件（项目根目录下的 tokens.txt）
        // 获取当前类所在的包路径：com/zwz5/common/user
        String packagePath = this.getClass()
                .getPackage()
                .getName()
                .replace(".", "/");

        // 拼接成 src/test/java/com/zwz5/common/user
        Path classDir = Paths.get(System.getProperty("user.dir"),
                "src/test/java",
                packagePath);

        // 生成 tokens.txt 文件
        Path tokenFile = classDir.resolve("tokens.txt");

        // 如果目录不存在，自动创建
        Files.createDirectories(tokenFile.getParent());

        try (BufferedWriter writer = Files.newBufferedWriter(
                tokenFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            // 3. 遍历用户，为每个用户生成 token、写入 Redis，并把 token 写入文件
            for (User user : users) {
                // 3.1 构造 UserDTO（和 login() 里一样）
                UserDTO userDTO = new UserDTO();
                BeanUtils.copyProperties(user, userDTO);

                // 3.2 DTO 转成 Map<String, String>
                Map<String, String> userMap = new HashMap<>();
                BeanMap.create(userDTO).forEach((k, v) ->
                        userMap.put(k.toString(), v == null ? "" : v.toString())
                );

                // 3.3 生成 token，并存入 Redis（和 login() 中保持一致）
                String token = UUID.randomUUID().toString();
                String redisKey = RedisConstants.LOGIN_USER_KEY + token;
                stringRedisTemplate.opsForHash().putAll(redisKey, userMap);
                stringRedisTemplate.expire(redisKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

                // 3.4 把 token 写入文件（一行一个）
                writer.write(token);
                writer.newLine();
            }
        }

        log.info("token 生成完成，已写入 tokens.txt");
    }
}