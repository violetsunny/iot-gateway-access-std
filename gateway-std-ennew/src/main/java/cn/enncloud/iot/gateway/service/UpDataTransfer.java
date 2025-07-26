package cn.enncloud.iot.gateway.service;

import cn.enncloud.iot.gateway.cache.DeviceCache;
import cn.enncloud.iot.gateway.cache.ModelMetricCache;
import cn.enncloud.iot.gateway.cache.VModelMetricCache;
import cn.enncloud.iot.gateway.kafka.KafkaProducer;
import cn.enncloud.iot.gateway.message.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.internal.LazilyParsedNumber;
import com.googlecode.aviator.AviatorEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.kdla.framework.common.help.MultiThreadInvokeHelp;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 上行数据中转
 *
 * @author hanyilong@enn.cn
 * @since 2021-02-08 16:07:47
 */
@SuppressWarnings("all")
@Slf4j
@Component
public class UpDataTransfer {

    @Resource
    private ExecutorService executorService;
    @Resource
    private RedisService redisService;
    @Resource
    private KafkaProducer kafkaProducer;
    @Resource
    private ModelMetricCache modelMetricCache;
    @Resource
    private DeviceCache deviceCache;
    @Resource
    private VModelMetricCache vModelMetricCache;

    @Value("${ennew.iot.topics.topic:enn_data_iot_metric}")
    private String deviceTopic;
    @Value("${ennew.iot.topics.eventTopic:data_iot_event}")
    private String eventTopic;
    @Value("${ennew.iot.topics.infoTopic:data_iot_device_info}")
    private String deviceInfoTopic;
    @Value("${ennew.iot.switch.filterMetric:false}")
    private boolean filterUnmatchMetricSwitch;
    @Value("${ennew.iot.switch.transformUnit:false}")
    private boolean transformUnit;

    private Consumer<Message> consumer = (t) -> {
        this.send(t);
    };

    public void sendMsg(Message msg) {
        List<Consumer<Message>> consumers = new ArrayList() {{
            add(consumer);
        }};
        MultiThreadInvokeHelp.executeC(consumers, msg, executorService);
    }

    private void send(Message msg) {
        try {
            if (msg instanceof MetricReportRequest) {
                handleReportMsg((MetricReportRequest) msg);
            } else if (msg instanceof EventReportRequest) {
                handleEventMsg((EventReportRequest) msg);
            } else if (msg instanceof InfoReportRequest) {
                handleInfoMsg((InfoReportRequest)msg);
            } else {
                log.warn("解析有问题：{}", JSON.toJSONString(msg));
            }
        } catch (Exception e) {
            log.error("报文消息发送失败：{}", JSON.toJSONString(msg), e);
        }

    }

    private void handleInfoMsg(InfoReportRequest reportRequest){
        //info报文上报，直接转发原始报文
        JSONObject infoMessage = reportRequest.getData();
        kafkaProducer.send(deviceInfoTopic, infoMessage);
    }

    private void handleReportMsg(MetricReportRequest deviceMessage) {
        String devId = deviceMessage.getDeviceId();
        Map<String, Object> entries = getDeviceAttrs(devId);
        if (MapUtils.isEmpty(entries)) {
            //todo 无法取得设备数据缓存的异常处理
            log.warn("无恩牛设备缓存:{}", devId);
            return;
        }
        String entityTypeCode = (String) entries.getOrDefault(RedisConstant.ENTITY_TYPE_CODE, null);
        if (StringUtils.isBlank(entityTypeCode)) {
            //todo 无法取得设备数据缓存的异常处理
            log.warn("恩牛设备缓存:{},entityTypeCode为空", devId);
            return;
        }
        String productId = (String) entries.getOrDefault(RedisConstant.PRODUCT_ID, null);
        if (StringUtils.isBlank(productId)) {
            //todo 无法取得设备数据缓存的异常处理
            log.warn("无恩牛设备缓存:{},productId为空", devId);
            return;
        }
        Map<String, JSONObject> originMetricMap = buildLocalNewMetricMap(productId, entityTypeCode);
        if (MapUtils.isEmpty(originMetricMap)) {
            //todo 无法取得产品元数据的异常处理
            log.warn("无产品测点数据缓存:{}", productId);
            return;
        }

        long timeStamp = deviceMessage.getTimeStamp() != 0L ? deviceMessage.getTimeStamp() : System.currentTimeMillis();
        long ts = String.valueOf(timeStamp).length() == 10 ? timeStamp * 1000 : timeStamp;
        //处理测点数据
        List<KafkaNewMessage.KafkaNewMetric> metrics = transformMetric(originMetricMap, devId, productId, deviceMessage.getMetrics(), ts);
        if (CollectionUtils.isEmpty(metrics)) {
            log.warn("{} {} 测点数据为空", devId, JSON.toJSONString(deviceMessage));
            return;
        }

        KafkaNewMessage message = new KafkaNewMessage();
        message.setVersion("0.0.1");
        message.setDevId(devId);
        //下游入库要时间戳为毫秒精度
        message.setTs(ts);
        long ingestionTime = deviceMessage.getIngestionTime() != 0L ? deviceMessage.getIngestionTime() : System.currentTimeMillis();
        message.setIngestionTime(String.valueOf(ingestionTime).length() == 10 ? ingestionTime * 1000 : ingestionTime);
        message.setResume(StringUtils.isBlank(deviceMessage.getResume()) ? "N" : deviceMessage.getResume());
        message.setChangeRpt(deviceMessage.isChangeRpt());
        message.setDevType(entityTypeCode);
        message.setDeviceName((String) entries.getOrDefault(RedisConstant.DEVICE_NAME, null));
        message.setPeriod((String) entries.getOrDefault(RedisConstant.PERIOD, null));
        message.setSn((String) entries.getOrDefault(RedisConstant.SN, null));
        message.setProductId(productId);
        message.setTenantId((String) entries.getOrDefault(RedisConstant.TENANT_ID, null));
        message.setDeptId((String) entries.getOrDefault(RedisConstant.DEPT_ID, null));
        message.setDebug((Integer) entries.getOrDefault(RedisConstant.TEST_FLAG, 0));
        message.setSource((String) entries.getOrDefault(RedisConstant.SOURCE, null));
        message.setStaId((String) entries.getOrDefault(RedisConstant.PROJECT_CODE, null));
        message.setDomain((String) entries.getOrDefault(RedisConstant.DOMAIN, null));
        message.setDeviceCode((String) entries.getOrDefault(RedisConstant.THIRD_CODE, null));
        message.setEntityTypeName((String) entries.getOrDefault(RedisConstant.ENTITY_TYPE_NAME, null));
        message.setUploadFrequency((String) entries.getOrDefault(RedisConstant.PERIOD, null));
        String county = (String) entries.getOrDefault(RedisConstant.AREA_CODE, null);
        if (StringUtils.isNotBlank(county) && county.length() >= 6) {
            message.setProvince(county.substring(county.length() - 4) + "0000");
            message.setCity(county.substring(county.length() - 2) + "00");
            message.setCounty(county);
        }
        message.setDeviceType((String) entries.getOrDefault(RedisConstant.DEVICE_TYPE, null));
        message.setParentId((String) entries.getOrDefault(RedisConstant.PARENT_ID, null));

        message.setData(metrics);
        kafkaProducer.send(deviceTopic, message);
    }

    private List<KafkaNewMessage.KafkaNewMetric> transformMetric(Map<String, JSONObject> originMetricMap, String deviceId, String productId, List<Metric> metrics, Long deviceTime) {
        //虚拟测点
        Map<String, Object> formulaRule = getMetricRuleFromRedis(deviceId);
        //处理测点数据
        List<KafkaNewMessage.KafkaNewMetric> newMetrics;
        if (MapUtils.isNotEmpty(formulaRule)) {
            log.info("{} {} 虚拟测点 {}", deviceId, productId, JSON.toJSONString(formulaRule));
            newMetrics = transformVMetricData(metrics, formulaRule, originMetricMap, deviceId, productId, deviceTime);
        } else {
            newMetrics = transformMetricData(originMetricMap, metrics);
        }
        return newMetrics;
    }

    private List<KafkaNewMessage.KafkaNewMetric> transformVMetricData(List<Metric> metrics, Map<String, Object> formulaRule, Map<String, JSONObject> metricMap, String deviceId, String productId, Long deviceTime) {
        //metrics改成Map会更快，ArrayList是O(n)，HashMap是O(1)
        Map<String, Metric> realMetricMap = metrics.stream().collect(Collectors.toMap(metric -> {
            if (MapUtils.isNotEmpty(metricMap)) {
                //大小写转换
                JSONObject originMetric = metricMap.get(metric.getCode().toUpperCase());
                if (Objects.nonNull(originMetric)) {
                    return originMetric.getString("code");
                }
            }
            return metric.getCode();
        }, Function.identity(), (oldValue, newValue) -> oldValue));

        //取合集 真实测点+物模型不存在测点+虚拟测点
        Set<String> metricSet = new HashSet<>(realMetricMap.keySet());
        metricSet.addAll(formulaRule.keySet());

        //O(mn)或O(mn^2)
        return metricSet.stream().map(metric -> {
            Metric metricOp = realMetricMap.get(metric);
            if (metricOp != null) {
                //存在测点
                JSONObject originMetric = null;
                if (MapUtils.isNotEmpty(metricMap)) {
                    originMetric = metricMap.get(metric.toUpperCase());
                }
                if (Objects.nonNull(originMetric)) {
                    KafkaNewMessage.KafkaNewMetric metricData = new KafkaNewMessage.KafkaNewMetric();
                    //存在于产品中的测点
                    metricData.setMetric(originMetric.getString("code"));
                    Object formula = formulaRule.get(originMetric.getString("code"));
                    if (formula != null) {
                        metricData.setValue(metricRules((String) formula, realMetricMap, metric, metricOp.getValue(), deviceId, productId));
                    } else {
                        metricData.setValue(metricOp.getValue());
                    }
                    metricData.setMetricUnit(originMetric.getString("unit"));
                    metricData.setMetricName(originMetric.getString("name"));
                    metricData.setMax(originMetric.getString("max"));
                    metricData.setMin(originMetric.getString("min"));
                    metricData.setType(originMetric.getString("type"));
                    long timeStamp = metricOp.getTs() != 0L ? metricOp.getTs() : System.currentTimeMillis();
                    metricData.setTs(String.valueOf(timeStamp).length() == 10 ? timeStamp * 1000 : timeStamp);
                    return metricData;
                } else {
                    //过滤开关关闭 不匹配的测点，继续传递给下游
                    if (!filterUnmatchMetricSwitch) {
                        KafkaNewMessage.KafkaNewMetric metricData = new KafkaNewMessage.KafkaNewMetric();
                        metricData.setMetric(metricOp.getCode());
                        Object formula = formulaRule.get(metricOp.getCode());
                        if (formula != null) {
                            metricData.setValue(metricRules((String) formula, realMetricMap, metric, metricOp.getValue(), deviceId, productId));
                        } else {
                            metricData.setValue(metricOp.getValue());
                        }
                        long timeStamp = metricOp.getTs() != 0L ? metricOp.getTs() : System.currentTimeMillis();
                        metricData.setTs(String.valueOf(timeStamp).length() == 10 ? timeStamp * 1000 : timeStamp);
                        return metricData;
                    }
                }

            } else {
                //虚拟测点
                String formula = (String) formulaRule.get(metric);
                if (StringUtils.isNotBlank(formula)) {

                    Object value = metricRules(formula, realMetricMap, metric, null, deviceId, productId);
                    //计算不了的虚拟测点，没必要上传
                    if (Objects.nonNull(value)) {
                        //存在产品测点
                        JSONObject originMetric = null;
                        if (MapUtils.isNotEmpty(metricMap)) {
                            originMetric = metricMap.get(metric.toUpperCase());
                        }
                        KafkaNewMessage.KafkaNewMetric metricData = new KafkaNewMessage.KafkaNewMetric();
                        metricData.setMetric(metric);
                        if (Objects.nonNull(originMetric)) {
                            //大小写
                            metricData.setMetric(originMetric.getString("code"));
                            metricData.setMetricUnit(originMetric.getString("unit"));
                            metricData.setMetricName(originMetric.getString("name"));
                            metricData.setMax(originMetric.getString("max"));
                            metricData.setMin(originMetric.getString("min"));
                            metricData.setType(originMetric.getString("type"));
                        }
                        metricData.setValue(value);
                        metricData.setTs(deviceTime);
                        return metricData;
                    }
                }
            }

            return null;
        }).filter(Objects::nonNull).collect(Collectors.toList());

    }

    private Object metricRules(String formula,Map<String, Metric> realMetricMap, String metric, Object metricValue, String deviceId, String productId){
        Object value = metricValue;
        try {

            if (formula.startsWith("[") && formula.endsWith("]")) {
                JSONArray jsonArray = JSON.parseArray(formula);
                String rule = transformMetricRules(jsonArray);
                if (StringUtils.isNotBlank(rule)) {
                    value = transformMetricCal(rule, realMetricMap, metric, metricValue, deviceId, productId);
                }
            } else if (formula.startsWith("{") && formula.endsWith("}")) {
                JSONObject jsonObject = JSON.parseObject(formula);
                String rule = transformMetricRule(jsonObject);
                if (StringUtils.isNotBlank(rule)) {
                    value = transformMetricCal(rule, realMetricMap, metric, metricValue, deviceId, productId);
                }
            } else {
                value = transformMetricCal(formula, realMetricMap, metric, metricValue, deviceId, productId);
            }

        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("{} {} transformMetricCal 计算测点异常：{}",deviceId,productId, formula, e);
            }
        }
        return value;
    }

    private Map<String, Object> getMetricRuleFromRedis(String deviceId) {
        if (!transformUnit) {
            return null;
        }
        return vMetricRuleFromRedis(deviceId);
    }

    private String transformMetricRules(JSONArray jsonArray) {
        for (Object object : jsonArray) {
            JSONObject jsonObject = (JSONObject) object;
            //有符合条件的就跳出
            String rule = transformMetricRule(jsonObject);
            if (StringUtils.isNotBlank(rule)) {
                return rule;
            }
        }
        return null;
    }

    private String transformMetricRule(JSONObject jsonObject) {
        String start = (String) jsonObject.get("start");
        long startT = System.currentTimeMillis();
        if (StringUtils.isNotBlank(start)) {
            startT = start.length() == 10 ? Long.parseLong(start) * 1000 : Long.parseLong(start);
        }
        String end = (String) jsonObject.get("end");
        long endT = System.currentTimeMillis();
        if (StringUtils.isNotBlank(end)) {
            endT = end.length() == 10 ? Long.parseLong(end) * 1000 : Long.parseLong(end);
        }
        if (System.currentTimeMillis() >= startT && System.currentTimeMillis() <= endT) {
            return (String) jsonObject.get("content");
        }
        return null;
    }

    private Object transformMetricCal(String rule, Map<String, Metric> realMetricMap, String metric, Object metricValue, String deviceId, String productId) {
        if (!transformUnit) {
            return metricValue;
        }
//        Pattern pattern = Pattern.compile("\\$\\{([^}]*)\\}");
        Matcher matcher = Pattern.compile("\\$\\{([^}]*)\\}").matcher(rule);
        Map<String, Object> attributes = new HashMap<>();
        while (matcher.find()) {
            //匹配获取测点
            String metricKey = matcher.group(1);
            Metric metricData = realMetricMap.get(metricKey);
            if (metricData != null) {
                rule = rule.replaceFirst("\\$\\{" + metricKey + "\\}", metricKey);
                Object value = metricData.getValue();
                Object number = transformNumber(value);
                if (number != null) {
                    attributes.putIfAbsent(metricKey, number);
                } else {
                    if (log.isInfoEnabled()) {
                        log.info("{} {} transformMetricCal 测点数值不是合法数字,formula={},metricKey={},value={}", deviceId, productId, rule, metricKey, JSON.toJSONString(metricData));
                    }
                }
            } else {
                //有一个测点没有上传就没法计算
                if (log.isInfoEnabled()) {
                    log.info("{} {} transformMetricCal 有测点没有上传数值,formula={},metricKey={}", deviceId, productId, rule, metricKey);
                }
                return metricValue;
            }
        }
        //TODO错误的rule，不要再计算，异常计算更耗时

        if (MapUtils.isEmpty(attributes)) {
            if (log.isWarnEnabled()) {
                log.warn("{} {} transformMetricCal 错误,metric={},formula={},attributes为空", deviceId, productId, metric, rule);
            }
            return metricValue;
        }

        Object value = null;
        try {
            value = AviatorEvaluator.execute(rule, attributes);
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("{} {} transformMetricCal error,metric={},formula={},attributes={}", deviceId, productId, metric, rule, JSON.toJSONString(attributes), e);
            }
            return metricValue;
        }

        if (value != null) {
            if ((value instanceof Double) && Double.isNaN((Double) value)) {
                if (log.isWarnEnabled()) {
                    log.warn("{} {} transformMetricCal 计算异常NaN,metric={},formula={},attributes={}", deviceId, productId, metric, rule, JSON.toJSONString(attributes));
                }
                return metricValue;
            }
            //真正计算出有效的结果值
            return value;
        } else {
            return metricValue;
        }

    }

    private static Object transformNumber(Object value) {
        if (value instanceof LazilyParsedNumber) {
            LazilyParsedNumber number = (LazilyParsedNumber) value;
            if (number != null) {
                value = number.toString();
            }
        }
        if (value instanceof String) {
            if (isInteger((String) value)) {
                value = Long.parseLong((String) value);
            } else if (isFloat((String) value)) {
                value = Double.parseDouble((String) value);
            } else if (isScientificNotation((String) value)) {
                value = Double.parseDouble((String) value);
            }
        }
        if (value instanceof Number) {
            return value;
        }

        return null;
    }

    public static boolean isInteger(String str) {
        return str.matches("^-?\\d+$");
    }

    public static boolean isFloat(String str) {
        return str.matches("^-?\\d+(\\.\\d+)?$");
    }

    //科学计数法
    public static boolean isScientificNotation(String str) {
        return str.matches("[+-]?\\d+(\\.\\d+)?([Ee][+-]?\\d+)?");
    }

    private List<KafkaNewMessage.KafkaNewMetric> transformMetricData(Map<String, JSONObject> originMetricMap, List<Metric> metrics) {
        List<KafkaNewMessage.KafkaNewMetric> newMetrics = new ArrayList<>();
        for (Metric entry : metrics) {
            KafkaNewMessage.KafkaNewMetric metric = new KafkaNewMessage.KafkaNewMetric();
            if (originMetricMap != null) {
                //屏蔽大小写，并补充数据
                JSONObject originMetric = originMetricMap.get(entry.getCode().toUpperCase());
                if (Objects.nonNull(originMetric)) {
                    metric.setMetric(originMetric.getString("code"));
                    metric.setValue(entry.getValue());
                    metric.setMetricUnit(originMetric.getString("unit"));
                    metric.setMetricName(originMetric.getString("name"));
                    metric.setMax(originMetric.getString("max"));
                    metric.setMin(originMetric.getString("min"));
                    metric.setType(originMetric.getString("type"));
                    metric.setTs(String.valueOf(entry.getTs()).length() == 10 ? entry.getTs() * 1000 : entry.getTs());
                    newMetrics.add(metric);
                    continue;
                }
            }
            //过滤开关关闭 不匹配的测点，继续传递给下游
            if (!filterUnmatchMetricSwitch) {
                metric.setMetric(entry.getCode());
                metric.setValue(entry.getValue());
                metric.setTs(String.valueOf(entry.getTs()).length() == 10 ? entry.getTs() * 1000 : entry.getTs());
                newMetrics.add(metric);
            }
        }

        return newMetrics;
    }

    private void handleEventMsg(EventReportRequest k) {
        KafkaEventMessage kafkaEventMessage = new KafkaEventMessage();
        kafkaEventMessage.setDevId(k.getDeviceId());
        kafkaEventMessage.setIdentifier(k.getIdentifier());
        //下游入库要时间戳为毫秒精度
        kafkaEventMessage.setTs(String.valueOf(k.getTimeStamp()).length() == 10 ? k.getTimeStamp() * 1000 : k.getTimeStamp());
        kafkaEventMessage.setTs(k.getTimeStamp());
        kafkaEventMessage.setVersion(k.getVersion());
        kafkaEventMessage.setType(k.getType());

        Map<String, Object> entries = getDeviceAttrs(k.getDeviceId());
        if (entries == null) {
            //todo 无法取得设备数据缓存的异常处理
            log.warn("无恩牛设备缓存:{}", k.getDeviceId());
            return;
        }
        kafkaEventMessage.setDevType((String) entries.getOrDefault(RedisConstant.ENTITY_TYPE_CODE, null));
        kafkaEventMessage.setProductId((String) entries.getOrDefault(RedisConstant.PRODUCT_ID, null));
        kafkaEventMessage.setDomain((String) entries.getOrDefault(RedisConstant.DOMAIN, null));
        kafkaEventMessage.setDeviceCode((String) entries.getOrDefault(RedisConstant.THIRD_CODE, null));
        kafkaEventMessage.setStaId((String) entries.getOrDefault(RedisConstant.PROJECT_CODE, null));
        kafkaEventMessage.setTenantId((String) entries.getOrDefault(RedisConstant.TENANT_ID, null));
        kafkaEventMessage.setSource("custom");
        List<Metric> metric = k.getMetrics();
        Map<String, Object> value = metric.stream().collect(Collectors.toMap(Metric::getCode, Metric::getValue, (oldV, newV) -> oldV));
        if (MapUtils.isNotEmpty(value) && !value.containsKey("eventStatus")) {
            value.put("eventStatus", 1);
        }
        kafkaEventMessage.setValue(value);
        kafkaProducer.send(eventTopic, kafkaEventMessage);
    }

    protected Map<String, Object> getDeviceAttrs(String deviceId) {
        Map<String, Object> deviceAttrMapCache = deviceCache.getFromLocal(deviceId);
        if (deviceAttrMapCache != null) {
            if (deviceAttrMapCache.containsKey("empty")) {
                return null;
            } else {
                return deviceAttrMapCache;
            }
        }
        Map<String, Object> deviceMap = redisService.getDeviceAttrsFromRedis(deviceId);
        if (MapUtils.isNotEmpty(deviceMap)) {
            deviceCache.putLocal(deviceId, deviceMap);
        } else {
            deviceMap = new HashMap<String, Object>() {{
                put("empty", "");
            }};
            deviceCache.putNullLocal(deviceId, deviceMap);
        }
        return deviceMap;
    }

    protected Map<String, JSONObject> buildLocalNewMetricMap(String productId, String entityTypeCode) {
        Map<String, JSONObject> metricMapCache = modelMetricCache.getFromLocal(entityTypeCode);
        if (metricMapCache != null) {
            if (metricMapCache.containsKey("empty")) {
                return null;
            } else {
                return metricMapCache;
            }
        }
        JSONArray measureProperties = redisService.getMeasurePsFromRedisByProduct(productId);
        if (measureProperties == null) {
            Map<String, JSONObject> metricMap = new HashMap<String, JSONObject>() {{
                put("empty", new JSONObject());
            }};
            modelMetricCache.putNullLocal(entityTypeCode, metricMap);
            return null;
        } else {
            Map<String, JSONObject> metricMap = measureProperties.stream().collect(Collectors.toMap(
                    e -> ((JSONObject) e).getString("code").toUpperCase(),
                    e -> (JSONObject) e,
                    (oldValue, newValue) -> oldValue)
            );
            modelMetricCache.putLocal(entityTypeCode, metricMap);
            return metricMap;
        }

    }

    protected Map<String, Object> vMetricRuleFromRedis(String deviceId) {
        Map<String, Object> vModelMetric = vModelMetricCache.getFromLocal(deviceId);
        if (MapUtils.isNotEmpty(vModelMetric)) {
            if (vModelMetric.containsKey("empty")) {
                return null;
            } else {
                return vModelMetric;
            }
        }
        vModelMetric = redisService.measureRules(deviceId);
        if (MapUtils.isNotEmpty(vModelMetric)) {
            vModelMetricCache.putLocal(deviceId, vModelMetric);
        } else {
            vModelMetric = new HashMap<String, Object>() {{
                put("empty", "");
            }};
            vModelMetricCache.putNullLocal(deviceId, vModelMetric);
        }
        return vModelMetric;
    }

//    public static void main(String[] args) {
//        Object n = 21488.398;
//        LazilyParsedNumber n2 = new LazilyParsedNumber("2148800909");
//        Object number = transformNumber(n2);
//        if (number == null) {
//            if (log.isInfoEnabled()) {
//                log.info("{}", n);
//            }
//        } else {
//            System.out.println(number);
//        }
//
//    }
}