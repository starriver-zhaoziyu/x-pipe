package com.ctrip.xpipe.redis.integratedtest.console.dr;

import com.ctrip.xpipe.api.server.Server;
import com.ctrip.xpipe.redis.console.controller.api.RetMessage;
import com.ctrip.xpipe.redis.core.entity.DcMeta;
import com.ctrip.xpipe.redis.core.foundation.IdcUtil;
import com.ctrip.xpipe.redis.core.meta.DcInfo;
import com.ctrip.xpipe.redis.integratedtest.console.AbstractXPipeClusterTest;
import com.ctrip.xpipe.redis.integratedtest.console.ForkProcessCmd;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

import static com.ctrip.xpipe.redis.console.config.impl.DefaultConsoleConfig.KEY_CLUSTER_SHARD_FOR_MIGRATE_SYS_CHECK;

/**
 * @author lishanglin
 * date 2021/1/21
 */
public class DRTest extends AbstractXPipeClusterTest {

    private Map<String, DcInfo> dcInfos;

    private Map<String, String> consoles;

    private Map<String, String> metaservers;

    private ForkProcessCmd jqMetaServer;
    private ForkProcessCmd oyMetaServer;

    private String zkJQ;

    private String zkOY;

    @Before
    public void setupDRTest() {
        dcInfos = new HashMap<>();
        dcInfos.put("jq", new DcInfo("http://127.0.0.1:" + IdcUtil.JQ_METASERVER_PORT));
        dcInfos.put("oy", new DcInfo("http://127.0.0.1:" + IdcUtil.OY_METASERVER_PORT));

        consoles = new HashMap<>();
        consoles.put("jq", "http://127.0.0.1:8080");
        consoles.put("oy", "http://127.0.0.1:8081");

        metaservers = new HashMap<>();
        metaservers.put("jq", dcInfos.get("jq").getMetaServerAddress());
        metaservers.put("oy", dcInfos.get("oy").getMetaServerAddress());

        zkJQ = "127.0.0.1:" + IdcUtil.JQ_ZK_PORT;
        zkOY = "127.0.0.1:" + IdcUtil.OY_ZK_PORT;
    }

    @After
    public void afterDRTest() throws IOException {
        cleanupAllSubProcesses();
        killAllRedisServers();
        cleanupConf();
    }

    @Override
    protected String prepareDatas() throws IOException {
        return prepareDatasFromFile("src/test/resources/xpipe-dr.sql");
    }

    @Test
    public void testNormalMigration() throws Exception {
        startSimpleXPipeDR();

        // check migration system
        waitForServerRespAsExpected("http://127.0.0.1:8080/api/migration/migration/system/health/status", RetMessage.class, RetMessage.createSuccessMessage(), 60000);

        // do migration
        tryMigration("http://127.0.0.1:8080", "cluster-dr", "jq", "oy");

        // check result
        waitForServerRespAsExpected("http://127.0.0.1:8081/api/health/127.0.0.1/6379", String.class, "\"HEALTHY\"", 30000);
    }

    @Test
    public void testMigrationWhenOriginDcMetaserverDown() throws Exception {
        startSimpleXPipeDR();

        // check migration system
        waitForServerRespAsExpected("http://127.0.0.1:8081/api/migration/migration/system/health/status", RetMessage.class, RetMessage.createSuccessMessage(), 60000);

        stopServer(jqMetaServer);

        if (jqMetaServer.isProcessAlive()) Assert.fail("jq metaserver is still alive after kill");

        // do migration
        tryMigration("http://127.0.0.1:8081", "cluster-dr", "jq", "oy");

        // check primary dc up
        waitForServerRespAsExpected("http://127.0.0.1:8081/api/health/127.0.0.1/7379", String.class, "\"HEALTHY\"", 30000);
        waitForRedisRole("127.0.0.1", 7379, Server.SERVER_ROLE.MASTER, 15000);

        // recover origin primary dc metaserver
        jqMetaServer = startMetaServer("jq", "http://127.0.0.1:8080", zkJQ, IdcUtil.JQ_METASERVER_PORT, dcInfos);

        // wait for repl recover
        waitForServerRespAsExpected("http://127.0.0.1:8081/api/health/127.0.0.1/6379", String.class, "\"HEALTHY\"", 120000);
    }

    protected void startSimpleXPipeDR() throws Exception {
        startZk(IdcUtil.JQ_ZK_PORT);
        startZk(IdcUtil.OY_ZK_PORT);

        startH2Server();
        setUpTestDataSource(); // init data in h2

        startRedis(6379);
        startRedis(7379);

        Map<String, String> extraOptions = Collections.singletonMap(KEY_CLUSTER_SHARD_FOR_MIGRATE_SYS_CHECK, "cluster-dr,cluster-dr-shard1");
        startConsole(8080, "jq", zkJQ, Collections.singletonList("127.0.0.1:8080"), consoles, metaservers, extraOptions);
        startConsole(8081, "oy", zkOY, Collections.singletonList("127.0.0.1:8081"), consoles, metaservers, extraOptions);

        String userDir = System.getProperty("user.dir");
        startKeepercontainer("jq", zkJQ, 7080, userDir + "/src/test/tmp/keepercontainer7080");
        startKeepercontainer("jq", zkJQ, 7081, userDir + "/src/test/tmp/keepercontainer7081");
        startKeepercontainer("oy", zkOY, 7180, userDir + "/src/test/tmp/keepercontainer7180");
        startKeepercontainer("oy", zkOY, 7181, userDir + "/src/test/tmp/keepercontainer7181");

        // wait for console init
        waitForServerAck("http://127.0.0.1:8080/api/dc/jq", DcMeta.class, 120000);
        waitForServerAck("http://127.0.0.1:8081/api/dc/oy", DcMeta.class, 60000);

        checkAllProcessAlive();

        jqMetaServer = startMetaServer("jq", "http://127.0.0.1:8080", zkJQ, IdcUtil.JQ_METASERVER_PORT, dcInfos);
        oyMetaServer = startMetaServer("oy", "http://127.0.0.1:8081", zkOY, IdcUtil.OY_METASERVER_PORT, dcInfos);

        // repl online
        waitForServerRespAsExpected("http://127.0.0.1:8080/api/health/127.0.0.1/7379", String.class, "\"HEALTHY\"", 120000);

        checkAllProcessAlive();
    }

}
