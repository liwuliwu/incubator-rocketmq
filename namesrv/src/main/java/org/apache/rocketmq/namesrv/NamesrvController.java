/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.namesrv;

import org.apache.rocketmq.common.Configuration;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.namesrv.kvconfig.KVConfigManager;
import org.apache.rocketmq.namesrv.processor.ClusterTestRequestProcessor;
import org.apache.rocketmq.namesrv.processor.DefaultRequestProcessor;
import org.apache.rocketmq.namesrv.routeinfo.BrokerHousekeepingService;
import org.apache.rocketmq.namesrv.routeinfo.RouteInfoManager;
import org.apache.rocketmq.remoting.RemotingServer;
import org.apache.rocketmq.remoting.netty.NettyRemotingServer;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
//总结
//1、nameserv通过clusterName来判断broker是不是属于同一个集群
//2、nameserv通过brokerName来判断两个broker是不是主从关系
//3、对于相同的brokerName，只有一个master（id=0）,不同的slave必须使用不同的Id (id>0)
//4、NameServ只会保存master的topic配置信息，因为理论上slave的topic信息是从master同步过去的
//5、所有的topic信息以broker上报为准，broker在启动的时候是不会去nameserv获取topic配置的，只会从自己持久化文件中加载。所以，一个新的broker启动后默认只有System topic信息。如果broker是新的，或者broker在挂掉一段时间重启topic不是最新的话，只能通过客户端更新topic来使broker能加入到正常的消息收发中。
public class NamesrvController {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

    private final NamesrvConfig namesrvConfig;

    private final NettyServerConfig nettyServerConfig;

    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl(
        "NSScheduledThread"));

    private final KVConfigManager kvConfigManager;
    private final RouteInfoManager routeInfoManager;

    private RemotingServer remotingServer;

    private BrokerHousekeepingService brokerHousekeepingService;

    private ExecutorService remotingExecutor;

    private Configuration configuration;

    public NamesrvController(NamesrvConfig namesrvConfig, NettyServerConfig nettyServerConfig) {
        //nameserv参数配置
        this.namesrvConfig = namesrvConfig;
        //netty的参数配置
        this.nettyServerConfig = nettyServerConfig;
        this.kvConfigManager = new KVConfigManager(this);
        //初始化RouteInfoManager
        // -->构造函数中初始化了RouteInfoManager，这个最重要的类，负责缓存整个集群的broker信息，以及topic和queue的配置信息。
        this.routeInfoManager = new RouteInfoManager();
        //监听客户端连接(Channel)的变化，通知RouteInfoManager检查broker是否有变化
        this.brokerHousekeepingService = new BrokerHousekeepingService(this);
        this.configuration = new Configuration(
            log,
            this.namesrvConfig, this.nettyServerConfig
        );
        //Nameserv的配置参数会保存到磁盘文件中
        this.configuration.setStorePathFromConfig(this.namesrvConfig, "configStorePath");
    }

    //以上最重要的就是第2，4步，初始化nameserv的Server，用来接收客户端请求。
    // 所有的客户端请求都会转给第4步中注册的DefaultRequestProcessor来处理。
    public boolean initialize() {

        //1、初始化KVConfigManager
        this.kvConfigManager.load();

        //2、初始化netty server
        this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.brokerHousekeepingService);

        //3、客户端请求处理的线程池
        this.remotingExecutor =
            Executors.newFixedThreadPool(nettyServerConfig.getServerWorkerThreads(), new ThreadFactoryImpl("RemotingExecutorThread_"));

        //4、注册DefaultRequestProcessor，所有的客户端请求都会转给这个Processor来处理
        this.registerProcessor();

        //第5步中，启动了一个定时器来扫描RouteInfoManager中缓存的broker信息，
        // 如果broker已经长时间没有心跳信息，则认为broker已经down掉了，将其移除。
        //5、启动定时调度，每10秒钟扫描所有Broker，检查存活状态
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                NamesrvController.this.routeInfoManager.scanNotActiveBroker();
            }
        }, 5, 10, TimeUnit.SECONDS);

        //6、日志打印的调度器，定时打印kvConfigManager的内容
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                NamesrvController.this.kvConfigManager.printAllPeriodically();
            }
        }, 1, 10, TimeUnit.MINUTES);

        return true;
    }

    private void registerProcessor() {
        if (namesrvConfig.isClusterTest()) {

            this.remotingServer.registerDefaultProcessor(new ClusterTestRequestProcessor(this, namesrvConfig.getProductEnvName()),
                this.remotingExecutor);
        } else {

            this.remotingServer.registerDefaultProcessor(new DefaultRequestProcessor(this), this.remotingExecutor);
        }
    }

    //启动的过程比较简单，就是启动netty server开始接收客户端请求。
    public void start() throws Exception {
        this.remotingServer.start();
    }

    public void shutdown() {
        this.remotingServer.shutdown();
        this.remotingExecutor.shutdown();
        this.scheduledExecutorService.shutdown();
    }

    public NamesrvConfig getNamesrvConfig() {
        return namesrvConfig;
    }

    public NettyServerConfig getNettyServerConfig() {
        return nettyServerConfig;
    }

    public KVConfigManager getKvConfigManager() {
        return kvConfigManager;
    }

    public RouteInfoManager getRouteInfoManager() {
        return routeInfoManager;
    }

    public RemotingServer getRemotingServer() {
        return remotingServer;
    }

    public void setRemotingServer(RemotingServer remotingServer) {
        this.remotingServer = remotingServer;
    }

    public Configuration getConfiguration() {
        return configuration;
    }
}
