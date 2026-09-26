package com.agentcode.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class SaverConfig {

    /**
     * 图检查点落 MySQL（GRAPH_THREAD / GRAPH_CHECKPOINT 两张表由 MysqlSaver 自己建）。
     *
     * <p>检查点按 threadId(=runId) 建索引，进程被杀后重启的实例能接着上次的节点继续跑，
     * 不再随 JVM 一起消失。
     */
    @Bean
    public BaseCheckpointSaver checkpointSaver(DataSource dataSource) {
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
    }
}
