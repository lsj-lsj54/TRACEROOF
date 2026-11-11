package com.traceproof;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.traceproof.mapper")
@EnableAsync
public class TraceProofApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraceProofApplication.class, args);
    }

}
