/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
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

package com.here.xyz.hub.rest;

import static com.here.xyz.hub.rest.Api.HeaderValues.APPLICATION_JSON;
import static com.jayway.restassured.RestAssured.given;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.hamcrest.Matchers.equalTo;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class UpdateSpaceWithExtensionApiIT extends TestSpaceWithFeature {

  @BeforeClass
  public static void setupClass() {
    removeSpace("x-psql-test-extension");
    removeSpace("x-psql-test");
    createSpace();
    createSpaceWithExtension();
  }

  @AfterClass
  public static void tearDownClass() {
    removeSpace("x-psql-test-extension");
    removeSpace("x-psql-test");
  }

  private static void createSpaceWithExtension() {
    given()
        .contentType(APPLICATION_JSON)
        .headers(getAuthHeaders(AuthProfile.ACCESS_OWNER_1_ADMIN))
        .body("{\"id\":\"x-psql-test-extension\",\"title\":\"x-psql-test-extension\",\"extends\":{\"spaceId\":\"x-psql-test\"}}")
        .when()
        .post("/spaces")
        .then()
        .statusCode(OK.code())
        .body("id", equalTo("x-psql-test-extension"))
        .body("extends.spaceId", equalTo("x-psql-test"));
  }

  @Test
  public void updateMutableSpaceProperties() {
    given()
        .contentType(APPLICATION_JSON)
        .headers(getAuthHeaders(AuthProfile.ACCESS_OWNER_1_ADMIN))
        .body("{\"title\":\"x-psql-test-extension-new-title\",\"description\":\"a test space which extends x-psql-test\"}")
        .when()
        .patch("/spaces/x-psql-test-extension")
        .then()
        .statusCode(OK.code())
        .body("id", equalTo("x-psql-test-extension"))
        .body("extends.spaceId", equalTo("x-psql-test"))
        .body("title", equalTo("x-psql-test-extension-new-title"))
        .body("description", equalTo("a test space which extends x-psql-test"));

    given()
        .headers(getAuthHeaders(AuthProfile.ACCESS_OWNER_1_ADMIN))
        .when()
        .get("/spaces/x-psql-test-extension")
        .then()
        .statusCode(OK.code())
        .body("id", equalTo("x-psql-test-extension"))
        .body("title", equalTo("x-psql-test-extension-new-title"))
        .body("extends.spaceId", equalTo("x-psql-test"));
  }

  @Test
  public void updateSpacePropertiesSamePayload() {
    given()
        .contentType(APPLICATION_JSON)
        .headers(getAuthHeaders(AuthProfile.ACCESS_OWNER_1_ADMIN))
        .body("{\"id\":\"x-psql-test-extension\",\"title\":\"x-psql-test-extension\",\"extends\":{\"spaceId\":\"x-psql-test\"}}")
        .when()
        .patch("/spaces/x-psql-test-extension")
        .then()
        .statusCode(OK.code());
  }

  @Test
  public void updateImmutableSpaceProperties() {
    given()
        .contentType(APPLICATION_JSON)
        .headers(getAuthHeaders(AuthProfile.ACCESS_ALL))
        .body("{\"searchableProperties\":{\"property1\":true}}")
        .when()
        .patch("/spaces/x-psql-test-extension")
        .then()
        .statusCode(BAD_REQUEST.code());

    given()
        .contentType(APPLICATION_JSON)
        .headers(getAuthHeaders(AuthProfile.ACCESS_ALL))
        .body("{\"storage\":{\"id\":\"psql\",\"params\":{\"foo\":\"bar\"}}}")
        .when()
        .patch("/spaces/x-psql-test-extension")
        .then()
        .statusCode(BAD_REQUEST.code())
        .body("errorMessage", equalTo("Validation failed. The properties 'storage' and 'extends' cannot be set together."));
  }
}