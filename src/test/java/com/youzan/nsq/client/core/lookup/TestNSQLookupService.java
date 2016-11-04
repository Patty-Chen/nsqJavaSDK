package com.youzan.nsq.client.core.lookup;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzan.nsq.client.configs.ConfigAccessAgent;
import com.youzan.nsq.client.entity.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class TestNSQLookupService {
    private static final Logger logger = LoggerFactory.getLogger(TestNSQLookupService.class);

    @DataProvider(name="genIPs")
    public Object[][] generateIPs() {
        Object[][] objs = new Object[2][2];

        String ips = "10.232.120.12:6411";
        List<String> expected = new ArrayList<>(10);
        expected.add("10.232.120.12:6411");
        objs[0][0] = ips;
        objs[0][1] = expected;

        ips = "10.232.120.13:6411";
        expected = new ArrayList<>(10);
        expected.add("10.232.120.13:6411");
        objs[1][0] = ips;
        objs[1][1] = expected;

        return objs;
    }

    @Test
    public void simpleInit() {
        try (LookupServiceImpl srv = new LookupServiceImpl(new String[]{"10.232.120.12:6411"}, Role.Producer)) {
            for (String addr : srv.getAddresses()) {
                Assert.assertTrue(addr.split(":").length == 2);
                Assert.assertEquals(addr, "10.232.120.12:6411");
            }
        }
    }

    private OutputStreamAppender addByteArrayOutputStreamAppender(Logger log) {
        // Destination stream
        ByteArrayOutputStream stream = new ByteArrayOutputStream();

        // Get LoggerContext from SLF4J
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        // Encoder
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{HH:mm:ss} %-5level %logger{36} - %msg%n");
        encoder.start();

        // OutputStreamAppender
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setName("OutputStream Appender");
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(stream);

        appender.start();

        ((ch.qos.logback.classic.Logger) log).addAppender(appender);
        return appender;
    }

    @Test
    /**
     * two points need verification here,
     * 1. http connection could fetch lookup stream to jackson;
     * 2. add Accept: application/nvd.nsq; version=1.0 header and lookup
     * returns right response
     */
    public void testFetchJsonFromLookUp() throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException, MalformedURLException {
        //create LookupServiceImpl via reflect, and inject appender into logger
        Class lookupClazz = LookupServiceImpl.class;
        LookupServiceImpl lsi = null;
        URL url = new URL("http://sqs-qa.s.qima-inc.com:4161/listlookup");
        lsi = new LookupServiceImpl(new String[]{url.toString()}, Role.Consumer);

        Method readFromURL = lookupClazz.getDeclaredMethod("readFromUrl", URL.class);
        readFromURL.setAccessible(true);
        JsonNode rootNode = (JsonNode) readFromURL.invoke(lsi, url);

        //verify, not status_code nor status_txt
        Assert.assertNull(rootNode.get("status_code"), "Response in listlookup service should NOT contain status_code");
        Assert.assertNull(rootNode.get("status_txt"), "Response in listlookup service should NOT contain status_txt");
        Assert.assertNotNull(rootNode.get("lookupdleader"), "Response in listlookUp service should contain lookupleader");
    }

    @Test
    public void makeBadOfLookup4ConnectionTimeoutTrace() throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException, InvocationTargetException, InstantiationException, UnsupportedEncodingException {
        logger.info("Begin to test a invalid lookup address 127.0.0.1 !");
        //create LookupServiceImpl via reflect, and inject appender into logger
        String[] lookupAddresses = new String[]{"127.0.0.1:2333"};
        LookupServiceImpl lsi = new LookupServiceImpl(lookupAddresses, Role.Producer);

        //fetch the logger, which is a static private
        Class<LookupServiceImpl> lookupClazz = LookupServiceImpl.class;
        Field logFld = lookupClazz.getDeclaredField("logger");
        logFld.setAccessible(true);
        Logger log = (Logger) logFld.get(lsi);

        //add appender to redirect log output
        OutputStreamAppender appender = addByteArrayOutputStreamAppender(log);
        try {
            lsi.start();
            //main thread sleeps for 60secs in order to give lookup service
            //enough time to run.
            Thread.sleep(60 * 1000L);
        } catch (InterruptedException e) {
        }
        ByteArrayOutputStream baos = (ByteArrayOutputStream) appender.getOutputStream();
        String logOutput = baos.toString("utf-8");
        Assert.assertTrue(logOutput.contains("Fail to connect to NSQ lookup. SDK Client, ip:"));
    }

    @Test(dataProvider = "genIPs")
    public void testInit(String ips, List<String> expected) {
        try (LookupServiceImpl srv = new LookupServiceImpl(new String[]{ips}, null)) {
            Assert.assertEquals(srv.getAddresses(), expected);
        }
    }

    @Test
    public void testJsonParser() throws IOException {
        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        JsonNode rootNode = mapper.readTree(
                "{\"channels\":[], \"partitions\":{\"0\":{\"id\":\"10.9.56.200:31454\",\"remote_address\":\"10.9.56.200:31454\",\"hostname\":\"qabb-qa-sqs2\",\"broadcast_address\":\"10.9.56.200\",\"tcp_port\":4150,\"http_port\":4152,\"version\":\"0.3.7-HA.1.3\",\"distributed_id\":\"10.9.56.200:4250:4150:136381\"},\"1\":{\"id\":\"10.9.80.209:39962\",\"remote_address\":\"10.9.80.209:39962\",\"hostname\":\"qabb-qa-sqs0\",\"broadcast_address\":\"10.9.80.209\",\"tcp_port\":4150,\"http_port\":4152,\"version\":\"0.3.7-HA.1.3\",\"distributed_id\":\"10.9.80.209:4250:4150:679756\"}},\"producers\":[{\"id\":\"10.9.56.200:31454\",\"remote_address\":\"10.9.56.200:31454\",\"hostname\":\"qabb-qa-sqs2\",\"broadcast_address\":\"10.9.56.200\",\"tcp_port\":4150,\"http_port\":4152,\"version\":\"0.3.7-HA.1.3\",\"distributed_id\":\"10.9.56.200:4250:4150:136381\"},{\"id\":\"10.9.80.209:39962\",\"remote_address\":\"10.9.80.209:39962\",\"hostname\":\"qabb-qa-sqs0\",\"broadcast_address\":\"10.9.80.209\",\"tcp_port\":4150,\"http_port\":4152,\"version\":\"0.3.7-HA.1.3\",\"distributed_id\":\"10.9.80.209:4250:4150:679756\"}]}"
        );
        JsonNode partitions = rootNode.get("partitions");

    }

    @AfterMethod
    public void release() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<ConfigAccessAgent> clazz = ConfigAccessAgent.class;
        Method method = clazz.getDeclaredMethod("release");
        method.setAccessible(true);
        method.invoke(ConfigAccessAgent.getInstance());
    }
}
