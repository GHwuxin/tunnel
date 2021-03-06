/*
 * Copyright 2018 Shanghai Junzheng Network Technology Co.,Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain CONFIG_NAME copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hellobike.base.tunnel.publisher.es;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson.JSON;
import com.hellobike.base.tunnel.config.EsConfig;
import com.hellobike.base.tunnel.model.ColumnData;
import com.hellobike.base.tunnel.model.Event;
import com.hellobike.base.tunnel.model.EventType;
import com.hellobike.base.tunnel.model.InvokeContext;
import com.hellobike.base.tunnel.model.Point;
import com.hellobike.base.tunnel.model.Shape;
import com.hellobike.base.tunnel.monitor.TunnelMonitorFactory;
import com.hellobike.base.tunnel.publisher.BasePublisher;
import com.hellobike.base.tunnel.publisher.IPublisher;
import com.hellobike.base.tunnel.spi.api.CollectionUtils;
import com.hellobike.base.tunnel.utils.NamedThreadFactory;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKBReader;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map.Entry;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.MapHandler;
import org.apache.commons.dbutils.handlers.MapListHandler;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.http.HttpHost;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.geotools.geojson.geom.GeometryJSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.hellobike.base.tunnel.utils.TimeUtils.sleepOneSecond;

/**
 * @author machunxiao 2018-10-25
 */
public class EsPublisher extends BasePublisher implements IPublisher {

    private static final Logger                         /**/ LOG = LoggerFactory.getLogger(EsPublisher.class);
    private static final int                            /**/ MAX_CACHED = 10240;

    private final List<EsConfig>                        /**/ esConfigs;
    private final ThreadPoolExecutor                    /**/ executor;

    private final LinkedBlockingQueue<Helper>           /**/ requestHelperQueue;
    private final RestHighLevelClient[]                 /**/ restClients;

    private final Map<String, DruidDataSource>          /**/ dataSources;


    private volatile boolean                            /**/ started;

    public EsPublisher(List<EsConfig> esConfigs) {
        this.esConfigs = esConfigs;
        int total = 8;
        this.executor = new ThreadPoolExecutor(total, total, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(5000), new NamedThreadFactory("EsSendThread"));

        this.restClients = new RestHighLevelClient[total];
        for (int i = 0; i < total; i++) {
            this.restClients[i] = newRestEsHighLevelClient();
        }
        this.dataSources = new ConcurrentHashMap<>();
        this.requestHelperQueue = new LinkedBlockingQueue<>(81920);

        started = true;
        for (int i = 0; i < total; i++) {
            this.executor.submit(new Sender(i));
        }

    }

    @Override
    public void publish(Event event, Callback callback) {
        throw new UnsupportedOperationException();

    }

    @Override
    public void publish(InvokeContext context, Callback callback) {
        this.esConfigs.forEach(
                esConfig -> internalPublish(context, callback, esConfig)
        );
    }

    @Override
    public void close() {
        this.started = false;
        this.executor.shutdown();
        Arrays.stream(this.restClients).forEach(this::closeClosable);
        this.dataSources.values().forEach(this::closeClosable);
        this.dataSources.clear();
        LOG.info("EsPublisher Closed");
    }

    private void closeClosable(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                //
            }
        }
    }

    private void internalPublish(InvokeContext context, Callback callback, EsConfig esConfig) {
        if (CollectionUtils.isEmpty(esConfig.getFilters())
                || esConfig.getFilters().stream().allMatch(filter -> filter.filter(context.getEvent()))) {
            sendToEs(esConfig, context, callback);
        }
    }

    private void sendToEs(EsConfig esConfig, InvokeContext context, Callback callback) {

        try {

            requestHelperQueue.put(new Helper(esConfig, context));

            onSuccess(callback);
        } catch (Exception e) {
            //
            LOG.error("Put Data To MemQueue Failure", e);
            onFailure(callback, e);
        }

        if (requestHelperQueue.size() >= MAX_CACHED) {
            forceFlushMemQueue(0);
        }

    }

    private static void insert(Map<String, Object> values, String key, Object value) {
        if (StringUtils.isEmpty(key)) {
            return;
        }
        String[] keys = key.split("\\.");
        if (keys.length == 0) {
            return;
        }
        Map<String, Object> r = values;
        for (int i = 0; i < keys.length - 1; i++) {
            Object obj = r.get(keys[i]);
            if (obj == null) {
                Map<String, Object> objInner = new HashMap<>();
                r.put(keys[i], objInner);
                r = objInner;
            } else {
                r = (Map<String, Object>) obj;
            }
        }
        r.put(keys[keys.length - 1], value);
    }

    public static Map<String, Object> toStructDoc(Map<String, Object> values) {
        Map<String, Object> result = new HashMap<>();
        for (Entry<String, Object> entry : values.entrySet()) {
            insert(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    private DocWriteRequest eventToRequest(EsConfig esConfig, EventType eventType, Map<String, ?> values) {
        DocWriteRequest req = null;
        Map<String, Object> valuesCopy = new HashMap<>();
        values.entrySet().stream().forEach(entry -> valuesCopy.put(entry.getKey(), entry.getValue()));
        // column_name,column_name
        String id = esConfig.getEsIdFieldNames()
                .stream()
                .map(esId -> String.valueOf(valuesCopy.get(esId)))
                .reduce((s1, s2) -> s1 + esConfig.getSeparator() + s2)
                .orElse("");

        if (StringUtils.isBlank(id)) {
            return null;
        }
        String type = esConfig.getType();
        String index = esConfig.getIndex();

        switch (eventType) {
            case INSERT:
            case UPDATE:
                UpdateRequest ur = new UpdateRequest(index, type, id);
                ur.doc(toStructDoc(valuesCopy));
                ur.docAsUpsert(true);
                req = ur;
                break;
            case DELETE:
                DeleteRequest dr = new DeleteRequest(index, type, id);
                dr.id(id);
                req = dr;
                break;
            default:
                break;
        }
        return req;
    }

    private Map<String, String> execSQL(EsConfig esConfig, InvokeContext context, Map<String, String> values) {
        String sql = esConfig.getSql();
        List<String> parameters = esConfig.getParameters();
        if (sql != null && parameters != null) {
            // select * from tb1 where id=@id,name=@name
            for (String k : parameters) {
                sql = sql.replace("?", getValue(values.get(k)));
            }

            // 执行sql,获得结果集
            Map<String, Object> result = executeQuery(sql, context);
            Map<String, String> newValues = new LinkedHashMap<>();
            if (!result.isEmpty()) {
                result.entrySet().stream()
                        .filter(e -> e.getValue() != null)
                        .forEach(e -> newValues.put(e.getKey(), String.valueOf(e.getValue())));
            }
            return newValues;
        }
        return values;
    }

    private String getValue(Object val) {
        if (val instanceof Number) {
            return val.toString();
        }
        return '\'' + val.toString() + '\'';
    }

    private void syncSend(RestHighLevelClient restClient, List<DocWriteRequest> doc) {

        long s = System.currentTimeMillis();
        BulkRequest br = createBulkRequest(doc);
        RequestOptions requestOptions = createRequestOptions();
        int retry = 10;

        while (retry > 0) {
            try {

                BulkResponse response = restClient.bulk(br, requestOptions);
                long e = System.currentTimeMillis();
                LOG.info("indexed doc:{},cost:{}ms,success:{}", doc.size(), e - s, !response.hasFailures());
                for (BulkItemResponse item : response.getItems()) {
                    if (item.isFailed()) {
                        LOG.error("OP: {}, index: {}, failure reason: {}", item.getOpType(), item.getFailure().getIndex(), item.getFailureMessage());
                    }
                }

                return;
            } catch (Exception e) {
                //
                retry--;
                LOG.error("Send Data To Es Occurred Error,retry:" + (10 - retry), e);
            }
        }
    }

    private BulkRequest createBulkRequest(List<DocWriteRequest> doc) {
        BulkRequest br = new BulkRequest();
        br.add(doc);
        br.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        br.waitForActiveShards(ActiveShardCount.ONE);
        return br;
    }

    private RequestOptions createRequestOptions() {
        RequestOptions.Builder builder = RequestOptions.DEFAULT.toBuilder();
        builder.addHeader("Connection", "Keep-Alive");
        return builder.build();
    }

    private List<Helper> pollHelperFromQueue(BlockingQueue<Helper> queue) {
        int len = queue.size();
        int capacity = Math.min(MAX_CACHED / 2, len);
        List<Helper> helpers = new ArrayList<>(capacity);
        queue.drainTo(helpers, capacity);
        return helpers;
    }

    private RestHighLevelClient newRestEsHighLevelClient() {
        return new RestHighLevelClient(RestClient.builder(
                this.esConfigs
                        .stream()
                        .map(esConfig -> HttpHost.create(esConfig.getServer()))
                        .toArray(HttpHost[]::new)
        ));
    }

    private void forceFlushMemQueue(int idx) {
        List<Helper> helpers = pollHelperFromQueue(requestHelperQueue);
        try {

            if (CollectionUtils.isEmpty(helpers)) {
                return;
            }

            Map<String, List<Helper>> data = helpers.stream()
                    .collect(Collectors.groupingBy(helper -> helper.esConfig.getTable()));
            for (List<Helper> list : data.values()) {
                if (list.isEmpty()) {
                    continue;
                }
                syncSend(restClients[idx], toRequests(list));

            }
        } finally {
            if (!helpers.isEmpty()) {
                Map<String, Long> data = getMonitorData(helpers);
                mapToStatics(data).forEach(statics ->
                        TunnelMonitorFactory.getTunnelMonitor().collect(statics)
                );
            }
        }
    }

    private List<DocWriteRequest> toRequests(List<Helper> helpers) {
        EsConfig esConfig = helpers.get(0).esConfig;

        String sql = esConfig.getSql();
        List<String> parameters = esConfig.getParameters();
        boolean sqlExists = StringUtils.isNotBlank(sql) && parameters != null && !parameters.isEmpty();
        if (sqlExists) {
            // select xx where id in (?)
            // select xx where (id,name) in (?)
            List<String> param = new ArrayList<>();
            for (Helper helper : helpers) {
                Map<String, String> values = helper.context.getEvent().getDataList()
                        .stream()
                        .collect(Collectors.toMap(ColumnData::getName, ColumnData::getValue));
                List<String> args = new ArrayList<>();
                for (String k : parameters) {
                    args.add(getValue(values.get(k)));
                }

                String arg = "(" + StringUtils.join(args, ",") + ")";
                param.add(arg);
            }

            sql = sql.replace("?", StringUtils.join(param, ","));

            List<Map<String, Object>> data = execute(sql, helpers.get(0).context);

            return data.stream()
                    .map(line -> {
                        Map<String, String> lines = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> e : line.entrySet()) {
                            lines.put(e.getKey(), String.valueOf(e.getValue()));
                        }
                        return lines;
                    })
                    .map(line -> eventToRequest(esConfig, EventType.INSERT, line))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else {
            return helpers.stream()
                    .map(helper -> {
                                Map<String, Object> r = new HashMap<>();
                                helper.context.getEvent().getDataList()
                                        .stream()
                                        .forEach(columnData -> {
                                            if (StringUtils.isNotEmpty(columnData.getValue())) {
                                                String columnName = columnData.getName().replace("\"", "");
                                                if ("geo_shape".equals(esConfig.getFieldMappings().get(columnName))) {
                                                    WKBReader reader = new WKBReader();
                                                    Geometry geometry = null;
                                                    try {
                                                        geometry = reader
                                                                .read(Bytes.fromHex(columnData.getValue()));
                                                        StringWriter writer = new StringWriter();
                                                        GeometryJSON g = new GeometryJSON(20);
                                                        g.write(geometry, writer);
                                                        Shape shape = JSON.parseObject(writer.toString(), Shape.class);
                                                        Map<String, Object> map = new HashMap<>();
                                                        map.put("type", shape.getType());
                                                        map.put("coordinates", shape.getCoordinates());
                                                        r.put(columnName, map);
                                                    } catch (ParseException | IOException e) {
                                                        e.printStackTrace();
                                                    }
                                                } else if ("geo_point".equals(esConfig.getFieldMappings().get(columnName))) {
                                                    WKBReader reader = new WKBReader();
                                                    Geometry geometry = null;
                                                    try {
                                                        geometry = reader
                                                                .read(Bytes.fromHex(columnData.getValue()));
                                                        StringWriter writer = new StringWriter();
                                                        GeometryJSON g = new GeometryJSON(20);
                                                        g.write(geometry, writer);
                                                        Point point = JSON.parseObject(writer.toString(), Point.class);
                                                        Map<String, Object> map = new HashMap<>();
                                                        map.put("type", point.getType());
                                                        map.put("coordinates", point.getCoordinates());
                                                        r.put(columnName, map);
                                                    } catch (ParseException | IOException e) {
                                                        e.printStackTrace();
                                                    }
                                                } else {
                                                    r.put(columnName, columnData.getValue());
                                                }
                                            }
                                        });
                                return eventToRequest(
                                        helper.esConfig,
                                        helper.context.getEvent().getEventType(), r
                                );
                            }
                    )
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

    }

    private List<Map<String, Object>> execute(String sql, InvokeContext context) {
        try (Connection conn = getConnection(context)) {
            QueryRunner qr = new QueryRunner();
            List<Map<String, Object>> list = qr.query(conn, sql, new MapListHandler());
            if (list == null) {
                list = new LinkedList<>();
            }
            return list;
        } catch (Exception e) {
            //
        }
        return new LinkedList<>();
    }

    private Map<String, Long> getMonitorData(List<Helper> helpers) {
        return helpers.stream()
                .map(helper -> {
                    helper.context.getEvent().setSlotName(helper.context.getSlotName());
                    return helper.context.getEvent();
                })
                .collect(Collectors.groupingBy(event -> event.getSchema() + "@@" + event.getSlotName() + "@@" + event.getTable() + "@@es", Collectors.counting()));
    }

    private Map<String, Object> executeQuery(String sql, InvokeContext context) {
        Connection connection = null;
        try {
            connection = getConnection(context);
            QueryRunner qr = new QueryRunner();
            Map<String, Object> query = qr.query(connection, sql, new MapHandler());
            if (query == null || query.isEmpty()) {
                query = new LinkedHashMap<>();
                LOG.warn("Select Nothing By SQL:{}", sql);
            }
            return query;
        } catch (Exception e) {
            //
        } finally {
            closeClosable(connection);
        }
        return new LinkedHashMap<>();
    }

    private Connection getConnection(InvokeContext ctx) throws SQLException {
        DruidDataSource dataSource = dataSources.get(ctx.getSlotName());
        if (dataSource == null) {
            DruidDataSource tmp;
            synchronized (this) {
                tmp = createDataSource(ctx);
            }
            dataSources.put(ctx.getSlotName(), tmp);
            dataSource = dataSources.get(ctx.getSlotName());
            LOG.info("DataSource Initialized. Slot:{},DataSource:{}", ctx.getSlotName(), dataSource.getName());
        }
        return dataSource.getConnection();
    }

    private DruidDataSource createDataSource(InvokeContext ctx) {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUsername(ctx.getJdbcUser());
        dataSource.setUrl(ctx.getJdbcUrl());
        dataSource.setPassword(ctx.getJdbcPass());
        dataSource.setValidationQuery("select 1");
        dataSource.setMinIdle(20);
        dataSource.setMaxActive(50);
        return dataSource;
    }

    private static class Helper {

        final EsConfig esConfig;
        final InvokeContext context;

        private Helper(EsConfig esConfig, InvokeContext context) {
            this.esConfig = esConfig;
            this.context = context;
        }
    }

    private class Sender implements Runnable {

        private final int idx;

        public Sender(int idx) {
            this.idx = idx;
        }

        @Override
        public void run() {
            while (started) {
                long s = System.currentTimeMillis();
                try {
                    forceFlushMemQueue(this.idx);
                } catch (Exception e) {
                    LOG.warn("flush data to es failure", e);
                } finally {
                    sleepOneSecond(s, System.currentTimeMillis());
                }
            }
        }

    }

}
