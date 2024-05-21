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

package com.here.xyz.jobs.steps.inputs;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.here.xyz.Typed;
import com.here.xyz.jobs.util.S3Client;
import java.util.List;
import java.util.stream.Collectors;

@JsonSubTypes({
    @JsonSubTypes.Type(value = UploadUrl.class, name = "UploadUrl")
})
public abstract class Input <T extends Input> implements Typed {
  @JsonIgnore
  private String s3Key;

  public static String inputS3Prefix(String jobId) {
    return jobId + "/inputs";
  }

  public String getS3Key() {
        return s3Key;
    }

  public void setS3Key(String s3Key) {
      this.s3Key = s3Key;
  }

  public T withS3Key(String s3Key) {
      setS3Key(s3Key);
      return (T) this;
  }

  public static List<Input> loadInputs(String jobId) {
    return S3Client.getInstance().scanFolder(Input.inputS3Prefix(jobId))
        .stream()
        .map(s3ObjectSummary -> new UploadUrl()
            .withS3Key(s3ObjectSummary.getKey())
            .withByteSize(s3ObjectSummary.getSize())
            //TODO: Run metadata retrieval requests partially in parallel in multiple threads
            .withCompressed(inputIsCompressed(s3ObjectSummary.getKey())))
        .collect(Collectors.toList());
  }

  private static boolean inputIsCompressed(String s3Key) {
    ObjectMetadata metadata = S3Client.getInstance().loadMetadata(s3Key);
    return metadata.getContentEncoding() != null && metadata.getContentEncoding().equalsIgnoreCase("gzip");
  }
}
