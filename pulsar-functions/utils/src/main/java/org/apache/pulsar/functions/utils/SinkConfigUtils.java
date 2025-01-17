/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.functions.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.pulsar.common.functions.ConsumerConfig;
import org.apache.pulsar.common.functions.FunctionConfig;
import org.apache.pulsar.common.functions.Resources;
import org.apache.pulsar.common.io.SinkConfig;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.functions.api.utils.IdentityFunction;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.Function.FunctionDetails;
import org.apache.pulsar.functions.utils.io.ConnectorUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.pulsar.functions.utils.FunctionCommon.convertProcessingGuarantee;
import static org.apache.pulsar.functions.utils.FunctionCommon.getSinkType;

@Slf4j
public class SinkConfigUtils {

    @Getter
    @Setter
    @AllArgsConstructor
    public static class ExtractedSinkDetails {
        private String sinkClassName;
        private String typeArg;
    }

    public static FunctionDetails convert(SinkConfig sinkConfig, ExtractedSinkDetails sinkDetails) throws IOException {
        FunctionDetails.Builder functionDetailsBuilder = FunctionDetails.newBuilder();

        boolean isBuiltin = !org.apache.commons.lang3.StringUtils.isEmpty(sinkConfig.getArchive()) && sinkConfig.getArchive().startsWith(org.apache.pulsar.common.functions.Utils.BUILTIN);

        if (sinkConfig.getTenant() != null) {
            functionDetailsBuilder.setTenant(sinkConfig.getTenant());
        }
        if (sinkConfig.getNamespace() != null) {
            functionDetailsBuilder.setNamespace(sinkConfig.getNamespace());
        }
        if (sinkConfig.getName() != null) {
            functionDetailsBuilder.setName(sinkConfig.getName());
        }
        functionDetailsBuilder.setRuntime(FunctionDetails.Runtime.JAVA);
        if (sinkConfig.getParallelism() != null) {
            functionDetailsBuilder.setParallelism(sinkConfig.getParallelism());
        } else {
            functionDetailsBuilder.setParallelism(1);
        }
        functionDetailsBuilder.setClassName(IdentityFunction.class.getName());
        if (sinkConfig.getProcessingGuarantees() != null) {
            functionDetailsBuilder.setProcessingGuarantees(
                    convertProcessingGuarantee(sinkConfig.getProcessingGuarantees()));
        }

        // set source spec
        // source spec classname should be empty so that the default pulsar source will be used
        Function.SourceSpec.Builder sourceSpecBuilder = Function.SourceSpec.newBuilder();
        sourceSpecBuilder.setSubscriptionType(Function.SubscriptionType.SHARED);
        if (sinkConfig.getInputs() != null) {
            sinkConfig.getInputs().forEach(topicName ->
                    sourceSpecBuilder.putInputSpecs(topicName,
                            Function.ConsumerSpec.newBuilder()
                                    .setIsRegexPattern(false)
                                    .build()));
        }
        if (!StringUtils.isEmpty(sinkConfig.getTopicsPattern())) {
            sourceSpecBuilder.putInputSpecs(sinkConfig.getTopicsPattern(),
                    Function.ConsumerSpec.newBuilder()
                            .setIsRegexPattern(true)
                            .build());
        }
        if (sinkConfig.getTopicToSerdeClassName() != null) {
            sinkConfig.getTopicToSerdeClassName().forEach((topicName, serde) -> {
                sourceSpecBuilder.putInputSpecs(topicName,
                        Function.ConsumerSpec.newBuilder()
                                .setSerdeClassName(serde == null ? "" : serde)
                                .setIsRegexPattern(false)
                                .build());
            });
        }
        if (sinkConfig.getTopicToSchemaType() != null) {
            sinkConfig.getTopicToSchemaType().forEach((topicName, schemaType) -> {
                sourceSpecBuilder.putInputSpecs(topicName,
                        Function.ConsumerSpec.newBuilder()
                                .setSchemaType(schemaType == null ? "" : schemaType)
                                .setIsRegexPattern(false)
                                .build());
            });
        }
        if (sinkConfig.getInputSpecs() != null) {
            sinkConfig.getInputSpecs().forEach((topic, spec) -> {
                Function.ConsumerSpec.Builder bldr = Function.ConsumerSpec.newBuilder()
                        .setIsRegexPattern(spec.isRegexPattern());
                if (!StringUtils.isBlank(spec.getSchemaType())) {
                    bldr.setSchemaType(spec.getSchemaType());
                } else if (!StringUtils.isBlank(spec.getSerdeClassName())) {
                    bldr.setSerdeClassName(spec.getSerdeClassName());
                }
                if (spec.getReceiverQueueSize() != null) {
                    bldr.setReceiverQueueSize(Function.ConsumerSpec.ReceiverQueueSize.newBuilder()
                            .setValue(spec.getReceiverQueueSize()).build());
                }
                sourceSpecBuilder.putInputSpecs(topic, bldr.build());
            });
        }

        if (sinkDetails.getTypeArg() != null) {
            sourceSpecBuilder.setTypeClassName(sinkDetails.getTypeArg());
        }
        if (isNotBlank(sinkConfig.getSourceSubscriptionName())) {
            sourceSpecBuilder.setSubscriptionName(sinkConfig.getSourceSubscriptionName());
        }

        Function.SubscriptionType subType = ((sinkConfig.getRetainOrdering() != null && sinkConfig.getRetainOrdering())
                || FunctionConfig.ProcessingGuarantees.EFFECTIVELY_ONCE.equals(sinkConfig.getProcessingGuarantees()))
                ? Function.SubscriptionType.FAILOVER
                : Function.SubscriptionType.SHARED;
        sourceSpecBuilder.setSubscriptionType(subType);

        if (sinkConfig.getAutoAck() != null) {
            functionDetailsBuilder.setAutoAck(sinkConfig.getAutoAck());
        } else {
            functionDetailsBuilder.setAutoAck(true);
        }
        if (sinkConfig.getTimeoutMs() != null) {
            sourceSpecBuilder.setTimeoutMs(sinkConfig.getTimeoutMs());
        }

        if (sinkConfig.getCleanupSubscription() != null) {
            sourceSpecBuilder.setCleanupSubscription(sinkConfig.getCleanupSubscription());
        } else {
            sourceSpecBuilder.setCleanupSubscription(true);
        }

        functionDetailsBuilder.setSource(sourceSpecBuilder);

        // set up sink spec
        Function.SinkSpec.Builder sinkSpecBuilder = Function.SinkSpec.newBuilder();
        if (sinkDetails.getSinkClassName() != null) {
            sinkSpecBuilder.setClassName(sinkDetails.getSinkClassName());
        }

        if (isBuiltin) {
            String builtin = sinkConfig.getArchive().replaceFirst("^builtin://", "");
            sinkSpecBuilder.setBuiltin(builtin);
        }

        if (sinkConfig.getConfigs() != null) {
            sinkSpecBuilder.setConfigs(new Gson().toJson(sinkConfig.getConfigs()));
        }
        if (sinkConfig.getSecrets() != null && !sinkConfig.getSecrets().isEmpty()) {
            functionDetailsBuilder.setSecretsMap(new Gson().toJson(sinkConfig.getSecrets()));
        }
        if (sinkDetails.getTypeArg() != null) {
            sinkSpecBuilder.setTypeClassName(sinkDetails.getTypeArg());
        }
        functionDetailsBuilder.setSink(sinkSpecBuilder);

        // use default resources if resources not set
        Resources resources = Resources.mergeWithDefault(sinkConfig.getResources());

        Function.Resources.Builder bldr = Function.Resources.newBuilder();
        bldr.setCpu(resources.getCpu());
        bldr.setRam(resources.getRam());
        bldr.setDisk(resources.getDisk());
        functionDetailsBuilder.setResources(bldr);

        if (isNotBlank(sinkConfig.getRuntimeFlags())) {
            functionDetailsBuilder.setRuntimeFlags(sinkConfig.getRuntimeFlags());
        }

        functionDetailsBuilder.setComponentType(FunctionDetails.ComponentType.SINK);

        return functionDetailsBuilder.build();
    }

    public static SinkConfig convertFromDetails(FunctionDetails functionDetails) {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setTenant(functionDetails.getTenant());
        sinkConfig.setNamespace(functionDetails.getNamespace());
        sinkConfig.setName(functionDetails.getName());
        sinkConfig.setParallelism(functionDetails.getParallelism());
        sinkConfig.setProcessingGuarantees(FunctionCommon.convertProcessingGuarantee(functionDetails.getProcessingGuarantees()));
        Map<String, ConsumerConfig> consumerConfigMap = new HashMap<>();
        for (Map.Entry<String, Function.ConsumerSpec> input : functionDetails.getSource().getInputSpecsMap().entrySet()) {
            ConsumerConfig consumerConfig = new ConsumerConfig();
            if (!isEmpty(input.getValue().getSerdeClassName())) {
                consumerConfig.setSerdeClassName(input.getValue().getSerdeClassName());
            }
            if (!isEmpty(input.getValue().getSchemaType())) {
                consumerConfig.setSchemaType(input.getValue().getSchemaType());
            }
            if (input.getValue().hasReceiverQueueSize()) {
                consumerConfig.setReceiverQueueSize(input.getValue().getReceiverQueueSize().getValue());
            }
            consumerConfig.setRegexPattern(input.getValue().getIsRegexPattern());
            consumerConfigMap.put(input.getKey(), consumerConfig);
        }
        sinkConfig.setInputSpecs(consumerConfigMap);
        if (!isEmpty(functionDetails.getSource().getSubscriptionName())) {
            sinkConfig.setSourceSubscriptionName(functionDetails.getSource().getSubscriptionName());
        }
        if (functionDetails.getSource().getSubscriptionType() == Function.SubscriptionType.FAILOVER) {
            sinkConfig.setRetainOrdering(true);
            sinkConfig.setProcessingGuarantees(FunctionConfig.ProcessingGuarantees.EFFECTIVELY_ONCE);
        } else {
            sinkConfig.setRetainOrdering(false);
            sinkConfig.setProcessingGuarantees(FunctionConfig.ProcessingGuarantees.ATLEAST_ONCE);
        }
        sinkConfig.setAutoAck(functionDetails.getAutoAck());
        if (functionDetails.getSource().getTimeoutMs() != 0) {
            sinkConfig.setTimeoutMs(functionDetails.getSource().getTimeoutMs());
        }
        if (!isEmpty(functionDetails.getSink().getClassName())) {
            sinkConfig.setClassName(functionDetails.getSink().getClassName());
        }
        if (!isEmpty(functionDetails.getSink().getBuiltin())) {
            sinkConfig.setArchive("builtin://" + functionDetails.getSink().getBuiltin());
        }
        if (!org.apache.commons.lang3.StringUtils.isEmpty(functionDetails.getSink().getConfigs())) {
            TypeReference<HashMap<String,Object>> typeRef
                    = new TypeReference<HashMap<String,Object>>() {};
            Map<String, Object> configMap;
            try {
               configMap = ObjectMapperFactory.getThreadLocal().readValue(functionDetails.getSink().getConfigs(), typeRef);
            } catch (IOException e) {
                log.error("Failed to read configs for sink {}", FunctionCommon.getFullyQualifiedName(functionDetails), e);
                throw new RuntimeException(e);
            }
            sinkConfig.setConfigs(configMap);
        }
        if (!isEmpty(functionDetails.getSecretsMap())) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> secretsMap = new Gson().fromJson(functionDetails.getSecretsMap(), type);
            sinkConfig.setSecrets(secretsMap);
        }
        if (functionDetails.hasResources()) {
            Resources resources = new Resources();
            resources.setCpu(functionDetails.getResources().getCpu());
            resources.setRam(functionDetails.getResources().getRam());
            resources.setDisk(functionDetails.getResources().getDisk());
        }

        if (isNotBlank(functionDetails.getRuntimeFlags())) {
            sinkConfig.setRuntimeFlags(functionDetails.getRuntimeFlags());
        }

        return sinkConfig;
    }

    public static ExtractedSinkDetails validate(SinkConfig sinkConfig, Path archivePath,
                                          File uploadedInputStreamAsFile) {
        if (isEmpty(sinkConfig.getTenant())) {
            throw new IllegalArgumentException("Sink tenant cannot be null");
        }
        if (isEmpty(sinkConfig.getNamespace())) {
            throw new IllegalArgumentException("Sink namespace cannot be null");
        }
        if (isEmpty(sinkConfig.getName())) {
            throw new IllegalArgumentException("Sink name cannot be null");
        }

        // make we sure we have one source of input
        Collection<String> allInputs = collectAllInputTopics(sinkConfig);
        if (allInputs.isEmpty()) {
            throw new IllegalArgumentException("Must specify at least one topic of input via topicToSerdeClassName, " +
                    "topicsPattern, topicToSchemaType or inputSpecs");
        }
        for (String topic : allInputs) {
            if (!TopicName.isValid(topic)) {
                throw new IllegalArgumentException(String.format("Input topic %s is invalid", topic));
            }
        }

        if (sinkConfig.getParallelism() != null && sinkConfig.getParallelism() <= 0) {
            throw new IllegalArgumentException("Sink parallelism must be a positive number");
        }

        if (sinkConfig.getResources() != null) {
            ResourceConfigUtils.validate(sinkConfig.getResources());
        }

        if (sinkConfig.getTimeoutMs() != null && sinkConfig.getTimeoutMs() <= 0) {
            throw new IllegalArgumentException("Sink timeout must be a positive number");
        }

        String sinkClassName;
        final Class<?> typeArg;
        final ClassLoader classLoader;
        if (!isEmpty(sinkConfig.getClassName())) {
            sinkClassName = sinkConfig.getClassName();
            // We really don't know if we should use nar class loader or regular classloader
            ClassLoader jarClassLoader = null;
            ClassLoader narClassLoader = null;
            try {
                jarClassLoader = FunctionCommon.extractClassLoader(archivePath, uploadedInputStreamAsFile);
            } catch (Exception e) {
            }
            try {
                narClassLoader = FunctionCommon.extractNarClassLoader(archivePath, uploadedInputStreamAsFile);
            } catch (Exception e) {
            }
            if (jarClassLoader == null && narClassLoader == null) {
                throw new IllegalArgumentException("Invalid Sink Package");
            }
            // We use typeArg and classLoader as arguments for lambda functions that require them to be final
            // Thus we use these tmp vars
            Class<?> tmptypeArg;
            ClassLoader tmpclassLoader;
            try {
                tmptypeArg = getSinkType(sinkClassName, narClassLoader);
                tmpclassLoader = narClassLoader;
            } catch (Exception e) {
                try {
                    tmptypeArg = getSinkType(sinkClassName, jarClassLoader);
                } catch (ClassNotFoundException e1) {
                    throw new IllegalArgumentException(
                            String.format("Sink class %s must be in class path", sinkClassName), e1);
                }
                tmpclassLoader = jarClassLoader;
            }
            typeArg = tmptypeArg;
            classLoader = tmpclassLoader;
        } else if (!org.apache.commons.lang3.StringUtils.isEmpty(sinkConfig.getArchive()) && sinkConfig.getArchive().startsWith(org.apache.pulsar.common.functions.Utils.FILE)) {
            throw new IllegalArgumentException("Class-name must be present for archive with file-url");
        } else {
            classLoader = FunctionCommon.extractNarClassLoader(archivePath, uploadedInputStreamAsFile);
            if (classLoader == null) {
                throw new IllegalArgumentException("Sink Package is not provided");
            }
            try {
                sinkClassName = ConnectorUtils.getIOSinkClass(classLoader);
            } catch (IOException e1) {
                throw new IllegalArgumentException("Failed to extract sink class from archive", e1);
            }
            try {
                typeArg = getSinkType(sinkClassName, classLoader);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException(
                        String.format("Sink class %s must be in class path", sinkClassName), e);
            }
        }

        if (sinkConfig.getTopicToSerdeClassName() != null) {
            sinkConfig.getTopicToSerdeClassName().forEach((topicName, serdeClassName) -> {
                ValidatorUtils.validateSerde(serdeClassName, typeArg, classLoader, true);
            });
        }

        if (sinkConfig.getTopicToSchemaType() != null) {
            sinkConfig.getTopicToSchemaType().forEach((topicName, schemaType) -> {
                ValidatorUtils.validateSchema(schemaType, typeArg, classLoader, true);
            });
        }

        // topicsPattern does not need checks

        if (sinkConfig.getInputSpecs() != null) {
            sinkConfig.getInputSpecs().forEach((topicName, consumerSpec) -> {
                // Only one is set
                if (!isEmpty(consumerSpec.getSerdeClassName()) && !isEmpty(consumerSpec.getSchemaType())) {
                    throw new IllegalArgumentException("Only one of serdeClassName or schemaType should be set");
                }
                if (!isEmpty(consumerSpec.getSerdeClassName())) {
                    ValidatorUtils.validateSerde(consumerSpec.getSerdeClassName(), typeArg, classLoader, true);
                }
                if (!isEmpty(consumerSpec.getSchemaType())) {
                    ValidatorUtils.validateSchema(consumerSpec.getSchemaType(), typeArg, classLoader, true);
                }
            });
        }
        return new ExtractedSinkDetails(sinkClassName, typeArg.getName());
    }

    private static Collection<String> collectAllInputTopics(SinkConfig sinkConfig) {
        List<String> retval = new LinkedList<>();
        if (sinkConfig.getInputs() != null) {
            retval.addAll(sinkConfig.getInputs());
        }
        if (sinkConfig.getTopicToSerdeClassName() != null) {
            retval.addAll(sinkConfig.getTopicToSerdeClassName().keySet());
        }
        if (sinkConfig.getTopicsPattern() != null) {
            retval.add(sinkConfig.getTopicsPattern());
        }
        if (sinkConfig.getTopicToSchemaType() != null) {
            retval.addAll(sinkConfig.getTopicToSchemaType().keySet());
        }
        if (sinkConfig.getInputSpecs() != null) {
            retval.addAll(sinkConfig.getInputSpecs().keySet());
        }
        return retval;
    }

    public static SinkConfig validateUpdate(SinkConfig existingConfig, SinkConfig newConfig) {
        SinkConfig mergedConfig = existingConfig.toBuilder().build();
        if (!existingConfig.getTenant().equals(newConfig.getTenant())) {
            throw new IllegalArgumentException("Tenants differ");
        }
        if (!existingConfig.getNamespace().equals(newConfig.getNamespace())) {
            throw new IllegalArgumentException("Namespaces differ");
        }
        if (!existingConfig.getName().equals(newConfig.getName())) {
            throw new IllegalArgumentException("Sink Names differ");
        }
        if (!StringUtils.isEmpty(newConfig.getClassName())) {
            mergedConfig.setClassName(newConfig.getClassName());
        }
        if (!StringUtils.isEmpty(newConfig.getSourceSubscriptionName()) && !newConfig.getSourceSubscriptionName().equals(existingConfig.getSourceSubscriptionName())) {
            throw new IllegalArgumentException("Subscription Name cannot be altered");
        }

        if (newConfig.getInputSpecs() == null) {
            newConfig.setInputSpecs(new HashMap<>());
        }

        if (mergedConfig.getInputSpecs() == null) {
            mergedConfig.setInputSpecs(new HashMap<>());
        }

        if (newConfig.getInputs() != null) {
            newConfig.getInputs().forEach((topicName -> {
                newConfig.getInputSpecs().put(topicName,
                        ConsumerConfig.builder().isRegexPattern(false).build());
            }));
        }
        if (newConfig.getTopicsPattern() != null && !newConfig.getTopicsPattern().isEmpty()) {
            newConfig.getInputSpecs().put(newConfig.getTopicsPattern(),
                    ConsumerConfig.builder()
                            .isRegexPattern(true)
                            .build());
        }
        if (newConfig.getTopicToSerdeClassName() != null) {
            newConfig.getTopicToSerdeClassName().forEach((topicName, serdeClassName) -> {
                newConfig.getInputSpecs().put(topicName,
                        ConsumerConfig.builder()
                                .serdeClassName(serdeClassName)
                                .isRegexPattern(false)
                                .build());
            });
        }
        if (newConfig.getTopicToSchemaType() != null) {
            newConfig.getTopicToSchemaType().forEach((topicName, schemaClassname) -> {
                newConfig.getInputSpecs().put(topicName,
                        ConsumerConfig.builder()
                                .schemaType(schemaClassname)
                                .isRegexPattern(false)
                                .build());
            });
        }
        if (!newConfig.getInputSpecs().isEmpty()) {
            newConfig.getInputSpecs().forEach((topicName, consumerConfig) -> {
                if (!existingConfig.getInputSpecs().containsKey(topicName)) {
                    throw new IllegalArgumentException("Input Topics cannot be altered");
                }
                if (consumerConfig.isRegexPattern() != existingConfig.getInputSpecs().get(topicName).isRegexPattern()) {
                    throw new IllegalArgumentException("isRegexPattern for input topic " + topicName + " cannot be altered");
                }
                mergedConfig.getInputSpecs().put(topicName, consumerConfig);
            });
        }
        if (newConfig.getProcessingGuarantees() != null && !newConfig.getProcessingGuarantees().equals(existingConfig.getProcessingGuarantees())) {
            throw new IllegalArgumentException("Processing Guarantess cannot be altered");
        }
        if (newConfig.getConfigs() != null) {
            mergedConfig.setConfigs(newConfig.getConfigs());
        }
        if (newConfig.getSecrets() != null) {
            mergedConfig.setSecrets(newConfig.getSecrets());
        }
        if (newConfig.getParallelism() != null) {
            mergedConfig.setParallelism(newConfig.getParallelism());
        }
        if (newConfig.getRetainOrdering() != null && !newConfig.getRetainOrdering().equals(existingConfig.getRetainOrdering())) {
            throw new IllegalArgumentException("Retain Orderning cannot be altered");
        }
        if (newConfig.getAutoAck() != null && !newConfig.getAutoAck().equals(existingConfig.getAutoAck())) {
            throw new IllegalArgumentException("AutoAck cannot be altered");
        }
        if (newConfig.getResources() != null) {
            mergedConfig.setResources(ResourceConfigUtils.merge(existingConfig.getResources(), newConfig.getResources()));
        }
        if (newConfig.getTimeoutMs() != null) {
            mergedConfig.setTimeoutMs(newConfig.getTimeoutMs());
        }
        if (!StringUtils.isEmpty(newConfig.getArchive())) {
            mergedConfig.setArchive(newConfig.getArchive());
        }
        if (!StringUtils.isEmpty(newConfig.getRuntimeFlags())) {
            mergedConfig.setRuntimeFlags(newConfig.getRuntimeFlags());
        }
        return mergedConfig;
    }
}