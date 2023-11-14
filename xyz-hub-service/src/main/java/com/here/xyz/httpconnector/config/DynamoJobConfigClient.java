/*
 * Copyright (C) 2017-2023 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */
package com.here.xyz.httpconnector.config;

import static com.here.xyz.httpconnector.util.jobs.Job.Status.failed;
import static com.here.xyz.httpconnector.util.jobs.Job.Status.finalized;
import static com.here.xyz.httpconnector.util.jobs.Job.Status.waiting;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Page;
import com.amazonaws.services.dynamodbv2.document.ScanFilter;
import com.amazonaws.services.dynamodbv2.document.ScanOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.DeleteItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.here.xyz.Payload;
import com.here.xyz.XyzSerializable;
import com.here.xyz.httpconnector.CService;
import com.here.xyz.httpconnector.util.jobs.CombinedJob;
import com.here.xyz.httpconnector.util.jobs.Job;
import com.here.xyz.httpconnector.util.jobs.Job.Status;
import com.here.xyz.hub.config.dynamo.DynamoClient;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;

/**
 * A client for writing and editing JOBs on a DynamoDb
 */
public class DynamoJobConfigClient extends JobConfigClient {

    private static final Logger logger = LogManager.getLogger();

    private final Table jobs;
    private final DynamoClient dynamoClient;
    private Long expiration;

    private static final String IO_IMPORT_ATTR_NAME = "importObjects";
    private static final String IO_EXPORT_ATTR_NAME = "exportObjects";

    public DynamoJobConfigClient(String tableArn) {
        dynamoClient = new DynamoClient(tableArn, null);
        logger.debug("Instantiating a reference to Dynamo Table {}", dynamoClient.tableName);
        jobs = dynamoClient.db.getTable(dynamoClient.tableName);
        if(CService.configuration != null && CService.configuration.JOB_DYNAMO_EXP_IN_DAYS != null)
            expiration = CService.configuration.JOB_DYNAMO_EXP_IN_DAYS;
    }

    @Override
    public Future<Void> init() {
        if (dynamoClient.isLocal()) {
            logger.info("DynamoDB running locally, initializing tables.");

            try {
                dynamoClient.createTable(jobs.getTableName(), "id:S,type:S,status:S", "id", "type,status", "exp");
            }
            catch (Exception e) {
                logger.error("Failure during creating tables on DynamoSpaceConfigClient init", e);
                return Future.failedFuture(e);
            }
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<Job> getJob(Marker marker, String jobId) {
        if(jobId == null)
            return Future.succeededFuture(null);
        return DynamoClient.dynamoWorkers.executeBlocking(p -> {
            try {
                GetItemSpec spec = new GetItemSpec()
                        .withPrimaryKey("id", jobId)
                        .withConsistentRead(true);

                Item jobItem = jobs.getItem(spec);

                if (jobItem == null) {
                    logger.info(marker, "job[{}] not found!", jobId);
                    p.complete();
                }
                else {
                    convertItemToJob(jobItem)
                        .onSuccess(job -> p.complete(job))
                        .onFailure(t -> p.fail(t));
                }
            }
            catch (Exception e) {
                p.fail(e);
            }
        });
    }

    @Override
    protected Future<List<Job>> getJobs(Marker marker, String type, Status status, String targetSpaceId) {
        return DynamoClient.dynamoWorkers.executeBlocking(p -> {
            try {
                List<ScanFilter> filterList = new ArrayList<>();

                if(type != null)
                    filterList.add(new ScanFilter("type").eq(type.toString()));
                if(status != null)
                    filterList.add(new ScanFilter("status").eq(status.toString()));
                if(targetSpaceId != null)
                    filterList.add(new ScanFilter("targetSpaceId").eq(targetSpaceId));

                List<Future<Job>> jobFutures = new ArrayList<>();
                jobs.scan(filterList.toArray(new ScanFilter[0])).pages().forEach(page -> page.forEach(item -> {
                    try{
                        jobFutures.add(convertItemToJob(item));
                    }catch (DecodeException e){
                        logger.warn("Cant decode Job-Item - skip!", e);
                    }
                }));

                Future.all(jobFutures)
                    .onSuccess(cf -> p.complete(cf.list()))
                    .onFailure(t -> p.fail(t));
            } catch (Exception e) {
                p.fail(e);
            }
        });
    }

    @Override
    protected Future<List<Job>> getJobs(Marker marker, Status status, String key, DatasetDirection direction) {
        return DynamoClient.dynamoWorkers.executeBlocking(p -> {
            try {

                List<String> filterExpression = new ArrayList<>();
                Map<String, String> nameMap = new HashMap<>();
                Map<String, Object> valueMap = new HashMap<>();

                if (status != null) {
                    nameMap.put("#status", "status");
                    valueMap.put(":status", status);
                    filterExpression.add("#status = :status");
                }

                if(key != null) {
                    nameMap.put("#sourceKey", "_sourceKey");
                    nameMap.put("#targetKey", "_targetKey");
                    valueMap.put(":key", key);
                    if(direction == DatasetDirection.SOURCE)
                        filterExpression.add("#sourceKey = :key");
                    else if(direction == DatasetDirection.TARGET)
                        filterExpression.add("#targetKey = :key");
                    else
                        filterExpression.add("(#sourceKey = :key OR #targetKey = :key)");
                }

                ScanSpec scanSpec = new ScanSpec()
                        .withFilterExpression(String.join(" AND ", filterExpression))
                        .withNameMap(nameMap)
                        .withValueMap(valueMap);

                List<Future<Job>> jobFutures = new ArrayList<>();
                jobs.scan(scanSpec).pages().forEach(page -> page.forEach(item -> {
                    try{
                        jobFutures.add(convertItemToJob(item));
                    }catch (DecodeException e){
                        logger.warn("Cant decode Job-Item - skip!", e);
                    }
                }));

                Future.all(jobFutures)
                    .onSuccess(cf -> p.complete(cf.list()))
                    .onFailure(t -> p.fail(t));
            } catch (Exception e) {
                p.fail(e);
            }
        });
    }

    protected Future<String> findRunningJobOnSpace(Marker marker, String targetSpaceId, String type) {
        return DynamoClient.dynamoWorkers.executeBlocking(p -> {
            try {
                Map<String, Object> expressionAttributeValues = ImmutableMap.of(
                    ":type", type.toString(),
                    ":spaceId", targetSpaceId,
                    ":waiting", waiting.toString(),
                    ":failed", failed.toString(),
                    ":finalized", finalized.toString()
                );
                List<String> conjunctions = ImmutableList.of(
                    "#type = :type",
                    "targetSpaceId = :spaceId",
                    "#status <> :waiting",
                    "#status <> :failed",
                    "#status <> :finalized"
                );
                ScanSpec scanSpec = new ScanSpec()
                    .withFilterExpression(String.join(" AND ", conjunctions))
                    .withNameMap(ImmutableMap.of("#type", "type", "#status", "status"))
                    .withValueMap(expressionAttributeValues);

                for (Page<Item, ScanOutcome> page : jobs.scan(scanSpec).pages())
                    for (Item item : page) {
                        p.complete(item.getString("id"));
                        return;
                    }

                p.complete(null);
            }
            catch (Exception e) {
                p.fail(e);
            }
        });
    }

    @Override
    protected Future<Job> deleteJob(Marker marker, Job job) {
        return DynamoClient.dynamoWorkers.executeBlocking(p -> {
            DeleteItemSpec deleteItemSpec = new DeleteItemSpec()
                    .withPrimaryKey("id", job.getId())
                    .withReturnValues(ReturnValue.ALL_OLD);
            jobs.deleteItem(deleteItemSpec);
            p.complete(job);
        });
    }

    @Override
    protected Future<Job> storeJob(Marker marker, Job job, boolean isUpdate) {
        //If exp is set we take it
        if (!isUpdate && this.expiration != null && job.getExp() == null) {
            job.setExp(System.currentTimeMillis() / 1000L + expiration * 24 * 60 * 60);

            if (job instanceof CombinedJob combinedJob)
                combinedJob.getChildren().stream().forEach(childJob -> childJob.setExp(job.getExp()));
        }

        return DynamoClient.dynamoWorkers.executeBlocking(p -> storeJobSync(job, p));
    }

    private void storeJobSync(Job job, Promise<Job> p) {
        Item item = convertJobToItem(job);
        jobs.putItem(item);
        p.complete(job);
    }

    private static Item convertJobToItem(Job job) {
        JsonObject json = JsonObject.mapFrom(job);
        if(job.getSource() != null)
            json.put("_sourceKey", job.getSource().getKey());
        if(job.getTarget() != null)
            json.put("_targetKey", job.getTarget().getKey());
        //TODO: Remove the following hacks from the persistence layer!
        if (json.containsKey(IO_IMPORT_ATTR_NAME))
            return convertJobToItem(json, IO_IMPORT_ATTR_NAME);
        else if (job instanceof CombinedJob && ((CombinedJob) job).getChildren().size() > 0)
            sanitizeChildren(json);
        else if (json.containsKey(IO_EXPORT_ATTR_NAME))
            sanitizeJob(json);
        return Item.fromJSON(json.toString());
    }

    private static void sanitizeJob(JsonObject json) {
        Map<String, Object> exportObjects = json.getJsonObject(IO_EXPORT_ATTR_NAME).getMap();
        sanitizeUrls(exportObjects);
        json.put(IO_EXPORT_ATTR_NAME, exportObjects);
    }

    private static void sanitizeChildren(JsonObject combinedJob) {
        JsonArray children = combinedJob.getJsonArray("children");
        JsonArray childIds = new JsonArray(children.stream().map(child -> ((JsonObject) child).getString("id"))
            .collect(Collectors.toList()));
        combinedJob.put("children", childIds);
    }

    private static void sanitizeUrls(Map<String, Object> exportObjects) {
        exportObjects.forEach((fileName, exportObject) -> ((Map<String, Object>) exportObject).remove("downloadUrl"));
    }

    private Future<Job> convertItemToJob(Item item){
        if(item.isPresent(IO_IMPORT_ATTR_NAME))
            return convertItemToJob(item, IO_IMPORT_ATTR_NAME);
        return convertItemToJob(item, IO_EXPORT_ATTR_NAME);
    }

    private static Item convertJobToItem(JsonObject json, String attrName) {
        String str = json.getJsonObject(attrName).encode();
        json.remove(attrName);
        Item item = Item.fromJSON(json.toString());
        return item.withBinary(attrName, compressString(str));
    }

    private Future<Job> convertItemToJob(Item item, String attrName) {
        JsonObject ioObjects = null;
        if(item.isPresent(attrName)) {
            try{
                ioObjects = new JsonObject(Objects.requireNonNull(uncompressString(item.getBinary(attrName))));
            }
            catch(Exception e){
                ioObjects = new JsonObject(item.getJSON(attrName));
            }
        }

        JsonObject json = new JsonObject(item.removeAttribute(attrName).toJSON())
                .put(attrName, ioObjects);

        Future<Void> resolvedFuture = Future.succeededFuture();
        if ("CombinedJob".equals(json.getString("type")))
            resolvedFuture = resolveChildren(json);
        try {
            final Job job = XyzSerializable.deserialize(json.toString(), Job.class);
            return resolvedFuture.map(v -> job);
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private Future<Void> resolveChildren(JsonObject combinedJob) {
        JsonArray children = combinedJob.getJsonArray("children");
        if (!children.isEmpty() && children.getValue(0) instanceof String) {
            List<Future<Job>> jobFutures = children.stream().map(childId -> getJob(null, (String) childId))
                .collect(Collectors.toList());
            return Future.all(jobFutures).compose(cf -> {
                combinedJob.put("children", new JsonArray(cf.list()));
                return Future.succeededFuture();
            });
        }
        return Future.succeededFuture();
    }

    private static byte[] compressString(String input) {
        if( input == null )
            return null;
        return Payload.compress(input.getBytes());
    }

    private static String uncompressString(byte[] input){
        try {
            return new String(Payload.decompress(input), StandardCharsets.UTF_8);
        }
        catch(Exception e) {
            return null;
        }
    }
}