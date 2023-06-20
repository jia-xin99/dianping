package com.dp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 添加redis地址，此处添加是单点的地址，也可使用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://192.168.200.100:6379").setPassword("123456");
        // 创建客户端
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient1() {
        Config config = new Config();
        // 添加redis地址，此处添加是单点的地址，也可使用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://192.168.200.100:6379").setPassword("123456");
        // 创建客户端
        return Redisson.create(config);
    }

    @Bean
    RedissonClient redissonClient2() {
        Config config = new Config();
        // 添加redis地址，此处添加是单点的地址，也可使用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://192.168.200.100:6379").setPassword("123456");
        // 创建客户端
        return Redisson.create(config);
    }

    @Bean
    RedissonClient redissonClient3() {
        Config config = new Config();
        // 添加redis地址，此处添加是单点的地址，也可使用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://192.168.200.100:6379").setPassword("123456");
        // 创建客户端
        return Redisson.create(config);
    }
}

