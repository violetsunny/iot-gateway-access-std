/**
 * llkang.com Inc.
 * Copyright (c) 2010-2024 All Rights Reserved.
 */
package cn.enncloud.iot.gateway.kafka;

import cn.enncloud.iot.gateway.message.Message;
import cn.enncloud.iot.gateway.service.UpDataTransfer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @author kanglele
 * @version $Id: kafkaDeviceConsumer, v 0.1 2024/5/16 16:54 kanglele Exp $
 */
@Component
@Slf4j
public class kafkaStdConsumer {

    @Resource
    private UpDataTransfer upDataTransfer;

    //@KafkaListener(topics = "#{'${spring.kafka.device.consumer.topics}'.split(',')}", containerFactory = "kafkaDeviceContainerFactory")
    public void listenerSource(ConsumerRecord<?, ?> record) {
        Object message = record.value();
        try {
            upDataTransfer.sendMsg((Message)message);
        } catch (Exception e) {
            log.warn("kafkaDeviceConsumer error msg：{}", message, e);
        }
    }

}
