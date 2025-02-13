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

package com.here.xyz.psql.query;

import static com.here.xyz.models.hub.Ref.HEAD;

import com.here.xyz.connectors.ErrorResponseException;
import com.here.xyz.events.ContextAwareEvent.SpaceContext;
import com.here.xyz.events.GetFeaturesByGeometryEvent;
import com.here.xyz.events.PropertiesQuery;
import com.here.xyz.models.geojson.implementation.Geometry;
import com.here.xyz.models.hub.Ref;
import com.here.xyz.psql.query.GetFeaturesByGeometryBuilder.GetFeaturesByGeometryInput;
import com.here.xyz.util.db.SQLQuery;
import java.sql.SQLException;
import java.util.Map;

public class GetFeaturesByGeometryBuilder extends XyzQueryBuilder<GetFeaturesByGeometryInput> {
  private SQLQuery additionalFilterFragment;

  @Override
  public SQLQuery buildQuery(GetFeaturesByGeometryInput input) throws QueryBuildingException {
    try {
      //TODO: Remove that workaround when refactoring is complete
      GetFeaturesByGeometryEvent event = new GetFeaturesByGeometryEvent()
          .withSpace(input.spaceId)
          .withConnectorParams(input.connectorParams)
          .withParams(input.spaceParams)
          .withVersionsToKeep(input.versionsToKeep)
          .withContext(input.context)
          .withRef(input.ref)
          .withPropertiesQuery(input.propertiesQuery)
          .withGeometry(input.geometry)
          .withRadius(input.radius)
          .withClip(input.clip);

      event.ignoreLimit = true;

      return new GetFeaturesByGeometryWithModifiedFilter(event)
          .<GetFeaturesByGeometry>withDataSourceProvider(getDataSourceProvider())
          .buildQuery(event);
    }
    catch (SQLException | ErrorResponseException e) {
      throw new QueryBuildingException(e);
    }
  }

  public GetFeaturesByGeometryBuilder withAdditionalFilterFragment(SQLQuery additionalFilterFragment) {
    this.additionalFilterFragment = additionalFilterFragment;
    return this;
  }

  public record GetFeaturesByGeometryInput(
      String spaceId,
      Map<String, Object> connectorParams,
      Map<String, Object> spaceParams,
      SpaceContext context,
      int versionsToKeep,
      Ref ref,
      Geometry geometry,
      int radius,
      boolean clip,
      PropertiesQuery propertiesQuery
  ) {
    public GetFeaturesByGeometryInput {
      if (ref == null)
        ref = new Ref(HEAD);
      if (geometry == null && clip)
        throw new IllegalArgumentException("Clip can not be applied if no filter geometry is provided.");
    }
  }

  private class GetFeaturesByGeometryWithModifiedFilter extends GetFeaturesByGeometry {

    public GetFeaturesByGeometryWithModifiedFilter(GetFeaturesByGeometryEvent event) throws SQLException, ErrorResponseException {
      super(event);
    }

    @Override
    protected SQLQuery buildFilterWhereClause(GetFeaturesByGeometryEvent event) {
      return patchWhereClause(super.buildFilterWhereClause(event), additionalFilterFragment);
    }

    private SQLQuery patchWhereClause(SQLQuery filterWhereClause, SQLQuery additionalFilterFragment) {
      if (additionalFilterFragment == null)
        return filterWhereClause;
      SQLQuery customizedWhereClause = new SQLQuery("${{innerFilterWhereClause}} AND ${{customWhereClause}}")
          .withQueryFragment("innerFilterWhereClause", filterWhereClause)
          .withQueryFragment("customWhereClause", additionalFilterFragment);
      return customizedWhereClause;
    }
  }
}
