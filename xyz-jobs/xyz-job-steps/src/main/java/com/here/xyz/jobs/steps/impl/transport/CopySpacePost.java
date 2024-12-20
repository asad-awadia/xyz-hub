/*
 * Copyright (C) 2017-2024 HERE Europe B.V.
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

package com.here.xyz.jobs.steps.impl.transport;

import static com.here.xyz.jobs.steps.Step.Visibility.USER;
import static com.here.xyz.jobs.steps.execution.LambdaBasedStep.ExecutionMode.SYNC;
import static com.here.xyz.jobs.steps.execution.db.Database.DatabaseRole.WRITER;
import static com.here.xyz.jobs.steps.execution.db.Database.loadDatabase;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.Phase.STEP_EXECUTE;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.infoLog;

import com.fasterxml.jackson.annotation.JsonView;
import com.here.xyz.jobs.steps.execution.StepException;
import com.here.xyz.jobs.steps.execution.db.Database;
import com.here.xyz.jobs.steps.impl.SpaceBasedStep;
import com.here.xyz.jobs.steps.inputs.InputFromOutput;
import com.here.xyz.jobs.steps.outputs.CreatedVersion;
import com.here.xyz.jobs.steps.outputs.FeatureStatistics;
import com.here.xyz.jobs.steps.resources.IOResource;
import com.here.xyz.jobs.steps.resources.Load;
import com.here.xyz.jobs.steps.resources.TooManyResourcesClaimed;
import com.here.xyz.models.hub.Space;
import com.here.xyz.util.db.SQLQuery;
import com.here.xyz.util.service.BaseHttpServerVerticle.ValidationException;
import com.here.xyz.util.service.Core;
import com.here.xyz.util.web.XyzWebClient.ErrorResponseException;
import com.here.xyz.util.web.XyzWebClient.WebClientException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * This step fetches the next unused version from source space.
 *
 * @TODO - onStateCheck - resume - provide output - add i/o report - move out parsePropertiesQuery functions
 */
public class CopySpacePost extends SpaceBasedStep<CopySpacePost> {

  public static final String STATISTICS = "statistics";
  private static final Logger logger = LogManager.getLogger();
  @JsonView({Internal.class, Static.class})
  private double overallNeededAcus = -1;

  @JsonView({Internal.class, Static.class})
  private long estimatedSourceFeatureCount = -1;

  @JsonView({Internal.class, Static.class})
  private long estimatedSourceByteSize = -1;

  @JsonView({Internal.class, Static.class})
  private long estimatedTargetFeatureCount = -1;

  @JsonView({Internal.class, Static.class})
  private int estimatedSeconds = -1;

  @JsonView({Internal.class, Static.class})
  private long copiedByteSize = 0;

  {
    setOutputSets(List.of(new OutputSet(STATISTICS, USER, true)));
  }

  @Override
  public List<Load> getNeededResources() {
    try {
      List<Load> rList = new ArrayList<>();
      Space sourceSpace = loadSpace(getSpaceId());

      rList.add(new Load().withResource(loadDatabase(sourceSpace.getStorage().getId(), WRITER))
          .withEstimatedVirtualUnits(calculateNeededAcus()));

      rList.add(new Load().withResource(IOResource.getInstance()).withEstimatedVirtualUnits(getCopiedByteSize())); // billing, reporting

      logger.info("[{}] IncVersion #{} {}", getGlobalStepId(),
          rList.size(),
          sourceSpace.getStorage().getId());

      return rList;
    }
    catch (WebClientException e) {
      throw new RuntimeException(e);
    }
  }

  private double calculateNeededAcus() {
    return 0.0;
  }

  public long getCopiedByteSize() {
    return copiedByteSize;
  }

  public void setCopiedByteSize(long copiedByteSize) {
    this.copiedByteSize = copiedByteSize;
  }

  @Override
  public int getTimeoutSeconds() {
    return 600;
  }

  @Override
  public int getEstimatedExecutionSeconds() {
    return 5;
  }

  @Override
  public String getDescription() {
    return "Increment Version sequence space " + getSpaceId();
  }

  @Override
  public boolean validate() throws ValidationException {
    super.validate();

    return true;
  }

  @Override
  public ExecutionMode getExecutionMode() {
    return SYNC;
  }

  @Override
  public void execute() throws Exception {
    long fetchedVersion = _getCreatedVersion();

    infoLog(STEP_EXECUTE, this, String.format("Get stats for version %d - %s", fetchedVersion, getSpaceId()));

    FeatureStatistics statistics = getCopiedFeatures(fetchedVersion);

    infoLog(STEP_EXECUTE, this, "Job Statistics: bytes=" + statistics.getByteSize() + " rows=" + statistics.getFeatureCount());
    registerOutputs(List.of(statistics), STATISTICS);

    setCopiedByteSize(statistics.getByteSize());
    if (statistics.getFeatureCount() > 0)
      writeContentUpdatedAtTs();
  }

  //TODO: Remove that workaround once the 3 copy steps were properly merged into one step again
  long _getCreatedVersion() {
    for (InputFromOutput input : (List<InputFromOutput>) (List<?>) loadInputs(InputFromOutput.class))
      if (input.getDelegate() instanceof CreatedVersion f)
        return f.getVersion();
    return 0;
  }

  private FeatureStatistics getCopiedFeatures(long fetchedVersion) throws SQLException, TooManyResourcesClaimed, WebClientException {

    Space targetSpace = loadSpace(getSpaceId());
    Database targetDb = loadDatabase(targetSpace.getStorage().getId(), WRITER);
    String targetSchema = getSchema(targetDb),
        targetTable = getRootTableName(targetSpace);

    SQLQuery incVersionSql = new SQLQuery(
        """
              select count(1), coalesce( sum( (coalesce(pg_column_size(jsondata),0) + coalesce(pg_column_size(geo),0))::bigint ), 0::bigint )
              from ${schema}.${table} 
              where version = ${{fetchedVersion}} 
            """)
        .withVariable("schema", targetSchema)
        .withVariable("table", targetTable)
        .withQueryFragment("fetchedVersion", "" + fetchedVersion);

    FeatureStatistics statistics = runReadQuerySync(incVersionSql, targetDb, 0, rs -> {
      return rs.next()
          ? new FeatureStatistics().withFeatureCount(rs.getLong(1)).withByteSize(rs.getLong(2))
          : new FeatureStatistics();
    });

    return statistics;
  }

  private void writeContentUpdatedAtTs() throws WebClientException {
    int nrRetries = 3;

    for (int i = 0; i < nrRetries; i++)
      try {
        hubWebClient().patchSpace(getSpaceId(), Map.of("contentUpdatedAt", Core.currentTimeMillis()));
        return;
      }
      catch (WebClientException e) {
        logger.error("[{}] writeContentUpdatedAtTs() -> retry({}) {}", getGlobalStepId(), i + 1, getSpaceId());
        sleepWithIncr(i + 1);
        throw new StepException("Error while updating the final target settings.", e)
            .withRetryable(e instanceof ErrorResponseException responseException && responseException.getErrorResponse().statusCode() == 504);
      }

    //TODO: handle ts not updated after retries -> should throw a "retryable Exception", when available. Ignore for now.
    logger.error("[{}] could not write contentUpadtedAt to space {}", getGlobalStepId(), getSpaceId());

  }

  private void sleepWithIncr(int i) {
    try {
      Thread.sleep(i * 15000);
    }
    catch (InterruptedException ignored) {
    }
  }

  @Override
  protected void onAsyncSuccess() throws WebClientException, SQLException, TooManyResourcesClaimed, IOException {
    logger.info("[{}] AsyncSuccess IncVersion {} ", getGlobalStepId(), getSpaceId());
  }

  @Override
  protected void onStateCheck() {
    //@TODO: Implement
    logger.info("ImlCopy.Post - onStateCheck");
    getStatus().setEstimatedProgress(0.2f);
  }

  @Override
  public void resume() throws Exception {
    //@TODO: Implement
    logger.info("ImlCopy.Post - onAsyncSuccess");
  }
}
