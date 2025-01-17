package com.newegg.ec.redis.schedule;

import com.newegg.ec.redis.client.RedisClientFactory;
import com.newegg.ec.redis.client.RedisURI;
import com.newegg.ec.redis.entity.*;
import com.newegg.ec.redis.service.IClusterService;
import com.newegg.ec.redis.service.INodeInfoService;
import com.newegg.ec.redis.service.IRedisService;
import com.newegg.ec.redis.util.RedisNodeInfoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import redis.clients.jedis.HostAndPort;

import java.util.*;

/**
 * @author Jay.H.Zou
 * @date 2019/7/22
 */
public abstract class NodeInfoCollectionAbstract implements IDataCollection, ApplicationListener<ContextRefreshedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(NodeInfoCollectionAbstract.class);

    @Autowired
    IClusterService clusterService;

    @Autowired
    private IRedisService redisService;

    @Autowired
    private INodeInfoService nodeInfoService;

    protected int coreSize;

    protected class CollectNodeInfoTask implements Runnable {

        private Cluster cluster;

        private Integer timeType;

        public CollectNodeInfoTask(Cluster cluster, Integer timeType) {
            this.cluster = cluster;
            this.timeType = timeType;
        }

        @Override
        public void run() {
            try {
                int clusterId = cluster.getClusterId();
                logger.debug("Start collecting cluster: " + cluster.getClusterName());
                RedisClientFactory.buildRedisClient(new RedisURI(cluster.getNodes(), cluster.getRedisPassword()));
                List<NodeInfo> nodeInfoList = getNodeInfoList(cluster, timeType);
                // clean last time data and save new data to db
                NodeInfoParam nodeInfoParam = new NodeInfoParam(clusterId, timeType);
                nodeInfoService.addNodeInfo(nodeInfoParam, nodeInfoList);
                if (TimeType.MINUTE.equals(timeType)) {
                    clusterService.updateCluster(cluster);
                }
            } catch (Exception e) {
                logger.error("Collect " + timeType + " data for " + cluster.getClusterName() + " failed.", e);
            }
        }
    }

    private List<NodeInfo> getNodeInfoList(Cluster cluster, Integer timeType) {
        String redisPassword = cluster.getRedisPassword();
        Set<HostAndPort> hostAndPortSet = getHostAndPortSet(cluster);
        List<NodeInfo> nodeInfoList = new ArrayList<>(hostAndPortSet.size());
        int clusterId = cluster.getClusterId();
        for (HostAndPort hostAndPort : hostAndPortSet) {
            NodeInfo nodeInfo = getNodeInfo(clusterId, hostAndPort, redisPassword, timeType);
            if (nodeInfo == null) {
                continue;
            }
            nodeInfo.setNode(hostAndPort.toString());
            nodeInfoList.add(nodeInfo);
        }
        return nodeInfoList;
    }

    private Set<HostAndPort> getHostAndPortSet(Cluster cluster) {
        List<RedisNode> redisNodeList = redisService.getRedisNodeList(cluster);
        Set<HostAndPort> hostAndPortSet = new HashSet<>();
        for (RedisNode redisNode : redisNodeList) {
            hostAndPortSet.add(new HostAndPort(redisNode.getHost(), redisNode.getPort()));
        }
        return hostAndPortSet;
    }

    private NodeInfo getNodeInfo(Integer clusterId, HostAndPort hostAndPort, String redisPassword, Integer timeType) {
        NodeInfo nodeInfo = null;
        String node = hostAndPort.toString();
        try {
            // 获取上一次的 NodeInfo 来计算某些字段的差值
            NodeInfoParam nodeInfoParam = new NodeInfoParam(clusterId, timeType, node);
            NodeInfo lastTimeNodeInfo = nodeInfoService.getLastTimeNodeInfo(nodeInfoParam);
            Map<String, String> infoMap = redisService.getNodeInfo(hostAndPort, redisPassword);
            // 指标计算处理
            nodeInfo = RedisNodeInfoUtil.parseInfoToObject(infoMap, lastTimeNodeInfo);
            nodeInfo.setLastTime(true);
            nodeInfo.setTimeType(timeType);
        } catch (Exception e) {
            logger.error("Build node info failed, node = " + node, e);
        }
        return nodeInfo;
    }
}
