package com.agentcode.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@RequiredArgsConstructor
public class SaverConfig {
    private final DataSource dataSource;


    @Bean
    public MemorySaver memorySaver(){
        MysqlSaver saver = MysqlSaver.builder().dataSource(dataSource).build();
        return saver;
    }
}
