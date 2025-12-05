package com.zwz5;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@MapperScan("com.zwz5.mapper")
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
public class HeimadianpingAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeimadianpingAppApplication.class, args);
    }

}
