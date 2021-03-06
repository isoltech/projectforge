/////////////////////////////////////////////////////////////////////////////
//
// Project ProjectForge Community Edition
//         www.projectforge.org
//
// Copyright (C) 2001-2014 Kai Reinhard (k.reinhard@micromata.de)
//
// ProjectForge is dual-licensed.
//
// This community edition is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License as published
// by the Free Software Foundation; version 3 of the License.
//
// This community edition is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
// Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, see http://www.gnu.org/licenses/.
//
/////////////////////////////////////////////////////////////////////////////

package org.projectforge.rest;

import java.util.Collection;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.projectforge.model.rest.RestPaths;
import org.projectforge.model.rest.TimesheetTemplateObject;
import org.projectforge.model.rest.UserObject;

import com.google.gson.reflect.TypeToken;

public class TimesheetTemplateClientMain
{
  private static final org.apache.log4j.Logger log = org.apache.log4j.Logger
      .getLogger(TimesheetTemplateClientMain.class);

  public static void main(final String[] args)
  {
    final Client client = ClientBuilder.newClient();
    final UserObject user = RestClientMain.authenticate(client);

    // http://localhost:8080/ProjectForge/rest/task/tree // userId / token
    final WebTarget webResource = client
        .target(RestClientMain.getUrl() + RestPaths.buildListPath(RestPaths.TIMESHEET_TEMPLATE));
    final Response response = RestClientMain.getClientResponse(webResource, user);
    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      throw new RuntimeException("Failed : HTTP error code : " + response.getStatus());
    }
    final String json = (String) response.getEntity();
    log.info(json);
    final Collection<TimesheetTemplateObject> col = JsonUtils.fromJson(json,
        new TypeToken<Collection<TimesheetTemplateObject>>()
        {
        }.getType());
    for (final TimesheetTemplateObject template : col) {
      log.info(template);
    }
  }
}
