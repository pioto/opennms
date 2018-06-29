/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.core.ipc.rpc.kafka;

import java.io.IOException;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.opennms.core.camel.JmsQueueNameFactory;
import org.opennms.core.ipc.rpc.kafka.model.RpcMessageProtos;
import org.opennms.core.rpc.api.RpcModule;
import org.opennms.core.rpc.api.RpcRequest;
import org.opennms.core.rpc.api.RpcResponse;
import org.opennms.core.rpc.echo.EchoRpcModule;
import org.opennms.minion.core.api.MinionIdentity;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

public class KafkaRpcServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRpcServerManager.class);
    public static final String KAFKA_CLIENT_PID = "org.opennms.core.ipc.rpc.kafka";
    private final Map<String, RpcModule<RpcRequest, RpcResponse>> registerdModules = new ConcurrentHashMap<>();
    private final Properties kafkaConfig = new Properties();
    private ConfigurationAdmin configAdmin;
    private KafkaProducer<String, byte[]> producer;
    private MinionIdentity minionIdentity;

    private final ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setNameFormat("kafka-consumer-rpc-server-%d")
            .build();
    private final ExecutorService executor = Executors.newCachedThreadPool(threadFactory);
    private Map<RpcModule<RpcRequest, RpcResponse>, KafkaConsumerRunner> rpcModuleConsumers = new ConcurrentHashMap<>();

    public KafkaRpcServerManager(ConfigurationAdmin configAdmin, MinionIdentity minionIdentity) {
        this.configAdmin = configAdmin;
        this.minionIdentity = minionIdentity;
    }

    public void init() throws IOException {
        // group.id is mapped to minion location, so one of the minion executes the request.
        kafkaConfig.put("group.id", minionIdentity.getLocation());
        kafkaConfig.put("enable.auto.commit", "true");
        kafkaConfig.put("key.deserializer", StringDeserializer.class.getCanonicalName());
        kafkaConfig.put("value.deserializer", ByteArrayDeserializer.class.getCanonicalName());
        kafkaConfig.put("auto.commit.interval.ms", "1000");
        kafkaConfig.put("key.serializer", StringSerializer.class.getCanonicalName());
        kafkaConfig.put("value.serializer", ByteArraySerializer.class.getCanonicalName());
        // Retrieve all of the properties from org.opennms.core.ipc.rpc.kafka.cfg
        final Dictionary<String, Object> properties = configAdmin.getConfiguration(KAFKA_CLIENT_PID).getProperties();
        if (properties != null) {
            final Enumeration<String> keys = properties.keys();
            while (keys.hasMoreElements()) {
                final String key = keys.nextElement();
                kafkaConfig.put(key, properties.get(key));
            }
        }
        LOG.info("KafkaRpcServerManager: initializing the Kafka producer with: {}", kafkaConfig);
        final ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // Class-loader hack for accessing the org.apache.kafka.common.serialization.ByteArraySerializer
            Thread.currentThread().setContextClassLoader(null);
            producer = new KafkaProducer<>(kafkaConfig);
        } finally {
            Thread.currentThread().setContextClassLoader(currentClassLoader);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void bind(RpcModule module) throws Exception {
        if (module != null) {
            final RpcModule<RpcRequest, RpcResponse> rpcModule = (RpcModule<RpcRequest, RpcResponse>) module;
            if (registerdModules.containsKey(rpcModule.getId())) {
                LOG.warn(" {} module is already registered", rpcModule.getId());
            } else {
                registerdModules.put(rpcModule.getId(), rpcModule);
                startConsumerForModule(rpcModule);
            }
        }
    }

    private void startConsumerForModule(RpcModule<RpcRequest, RpcResponse> rpcModule) {
        final JmsQueueNameFactory topicNameFactory = new JmsQueueNameFactory("rpc-request", rpcModule.getId(),
                minionIdentity.getLocation());
        KafkaConsumer<String, byte[]> consumer;
        final ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // Class-loader hack for accessing the org.apache.kafka.common.serialization.ByteArraySerializer
            Thread.currentThread().setContextClassLoader(null);
            consumer = new KafkaConsumer<String, byte[]>(kafkaConfig);
        } finally {
            Thread.currentThread().setContextClassLoader(currentClassLoader);
        }
        KafkaConsumerRunner kafkaConsumerRunner = new KafkaConsumerRunner(rpcModule, consumer, topicNameFactory.getName());
        executor.execute(kafkaConsumerRunner);
        LOG.info("started kafka consumer for module : {}", rpcModule.getId());
        rpcModuleConsumers.put(rpcModule, kafkaConsumerRunner);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void unbind(RpcModule module) throws Exception {
        if (module != null) {
            final RpcModule<RpcRequest, RpcResponse> rpcModule = (RpcModule<RpcRequest, RpcResponse>) module;
            registerdModules.remove(rpcModule.getId());
            stopConsumerForModule(rpcModule);
        }
    }

    private void stopConsumerForModule(RpcModule<RpcRequest, RpcResponse> rpcModule) {
        KafkaConsumerRunner kafkaConsumerRunner  = rpcModuleConsumers.remove(rpcModule);
        LOG.info("stopped kafka consumer for module : {}", rpcModule.getId());
        kafkaConsumerRunner.shutdown();
    }

    public void destroy() {

    }

    public void setConfigAdmin(ConfigurationAdmin configAdmin) {
        this.configAdmin = configAdmin;
    }

    private class KafkaConsumerRunner implements Runnable {

        private final KafkaConsumer<String, byte[]> consumer;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private String topic;
        private RpcModule<RpcRequest, RpcResponse> module;

        public KafkaConsumerRunner(RpcModule<RpcRequest, RpcResponse> rpcModule, KafkaConsumer<String, byte[]> consumer, String topic) {
            this.consumer = consumer;
            this.topic = topic;
            this.module = rpcModule;
        }

        public void shutdown() {
            closed.set(true);
            consumer.wakeup();
        }

        @Override
        public void run() {
            try {
                consumer.subscribe(Arrays.asList(topic));
                LOG.info("subscribed to topic {}", topic);
                while (!closed.get()) {
                    ConsumerRecords<String, byte[]> records = consumer.poll(100);
                    for (ConsumerRecord<String, byte[]> record : records) {
                        if (module.getId().equals(EchoRpcModule.RPC_MODULE_ID)) {
                            String minionId = getMinionIdentity().getId();
                            if (!(minionId.equals(record.key()))) {
                                LOG.info("MinionIdentity {} doesn't match with key {}, ignore the request", minionId, record.key());
                                continue;
                            }
                        }
                        RpcMessageProtos.RpcMessage rpcMessage = RpcMessageProtos.RpcMessage.parseFrom(record.value());
                        String rpcId = rpcMessage.getRpcId();
                        RpcRequest request = module.unmarshalRequest(rpcMessage.getRpcContent().toStringUtf8());
                        CompletableFuture<RpcResponse> future = module.execute(request);
                        future.whenComplete((res, ex) -> {
                            final RpcResponse response;
                            if (ex != null) {
                                // An exception occurred, store the exception in a new response
                                LOG.warn("An error occured while executing a call in {}.", module.getId(), ex);
                                response = module.createResponseWithException(ex);
                            } else {
                                // No exception occurred, use the given response
                                response = res;
                            }
                            String responseAsString = null;
                            try {
                                responseAsString = module.marshalResponse(response);
                                final JmsQueueNameFactory topicNameFactory = new JmsQueueNameFactory("rpc-response",
                                        module.getId());
                                RpcMessageProtos.RpcMessage rpcResponse = RpcMessageProtos.RpcMessage.newBuilder()
                                                                              .setRpcId(rpcId)
                                                                              .setRpcContent(ByteString.copyFromUtf8(responseAsString))
                                                                              .build();
                                final ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(
                                        topicNameFactory.getName(), rpcResponse.toByteArray());
                                producer.send(producerRecord);
                                LOG.debug("request {} executed, sending response {} ", request.toString(),
                                        responseAsString);
                            } catch (Throwable t) {
                                LOG.error("Marshalling response in RPC module {} failed.", module, t);
                            }
                        });
                    }
                }
            } catch (WakeupException e) {
                // Ignore exception if closing
                if (!closed.get()) {
                    throw e;
                }
            } catch (InvalidProtocolBufferException e) {
                LOG.error(" Error while parsing request for module {}", module.getId(), e);
            } finally {
                consumer.close();
            }

        }

    }

    public MinionIdentity getMinionIdentity() {
        return minionIdentity;
    }
}
;