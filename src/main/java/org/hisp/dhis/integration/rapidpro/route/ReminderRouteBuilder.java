/*
 * Copyright (c) 2004-2022, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.integration.rapidpro.route;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.hisp.dhis.integration.rapidpro.aggregationStrategy.MapAggregationStrategy;
import org.hisp.dhis.integration.rapidpro.processor.SetReportRateQueryParamProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ReminderRouteBuilder extends AbstractRouteBuilder
{
    @Autowired
    private SetReportRateQueryParamProcessor setReportRateQueryParamProcessor;

    @Override
    protected void doConfigure()
    {
        from( "servlet:tasks/reminders?muteException=true" )
            .removeHeaders( "*" )
            .to( "direct:reminders" )
            .setHeader( Exchange.CONTENT_TYPE, constant( "application/json" ) )
            .setBody( constant( Map.of("status", "success", "data", "Sent reminders of overdue reports") ) )
            .marshal().json();

        from( "quartz://reminders?cron={{reminder.schedule.expression:0 0 9 ? * *}}" )
            .to( "direct:reminders" );

        from( "direct:reminders" )
            .routeId( "Broadcast Reminders" )
            .log( LoggingLevel.INFO, LOGGER, "Reminding RapidPro contacts of overdue reports..." )
            .setProperty( "orgUnitIdScheme", simple( "{{org.unit.id.scheme}}" ) )
            .choice().when( simple( "{{sync.rapidpro.contacts}} == true" ) )
                .to( "direct:sync" )
            .end()
            .split( simple( "{{reminder.data.set.codes:}}" ), "," )
                .setProperty( "dataSetCode", body() )
                .to( "direct:fetchDataSet" )
                .choice().when( body().isNull() )
                    .log( LoggingLevel.WARN, LOGGER, "Cannot remind contacts given unknown data set code '${exchangeProperty.dataSetCode}'" )
                .otherwise()
                    .setProperty( "dataSet", body() )
                    .setHeader( "group", constant( "DHIS2" ) )
                    .to("kamelet:hie-rapidpro-get-contacts-sink?rapidProApiToken={{rapidpro.api.token}}&rapidProApiUrl={{rapidpro.api.url}}")
                    .split(body(), new MapAggregationStrategy() )
                        .setBody().groovy( "[(body.fields.dhis2_organisation_unit_id) : body.uuid]" )
                    .end()
                    .setProperty( "orgUnitIdsAndContactIds", body() )
                    .to( "direct:fetchReportRate" )
                    .split( simple( "${body['rows']}" ) )
                        .filter().ognl(  "@java.lang.Double@parseDouble(request.body[4]) < 100" )
                        .to( "direct:sendBroadcast" )
                    .end()
                .end()
            .end();

        from( "direct:fetchDataSet" )
            .toD( "dhis2://get/resource?path=dataSets&filter=code:eq:${body}&fields=id,name,periodType,organisationUnits[id,${exchangeProperty.orgUnitIdScheme.toLowerCase()}]&client=#dhis2Client" )
            .setProperty( "dataSetCount", jsonpath( "$.dataSets.length()" ) )
            .choice().when().simple( "${exchangeProperty.dataSetCount} > 0" )
                .transform( jsonpath( "$.dataSets[0]" ) )
            .otherwise()
                .setBody( simple( "${null}" ) )
            .end();

        from( "direct:fetchReportRate" )
            .process( setReportRateQueryParamProcessor )
            .to( "dhis2://get/resource?path=analytics&client=#dhis2Client" )
            .unmarshal().json( Map.class );

        from( "direct:sendBroadcast" )
            .setHeader( "contacts",
                datasonnet( "resource:classpath:broadcastContacts.ds", List.class, "application/x-java-object",
                    "application/x-java-object" ) )
            .setHeader( "text" ).groovy(
                "java.text.MessageFormat.format(java.util.ResourceBundle.getBundle('reminder').getString('text'), exchangeProperties.dataSet.name)" )
            .to( "kamelet:hie-rapidpro-send-broadcast-sink?rapidProApiToken={{rapidpro.api.token}}&rapidProApiUrl={{rapidpro.api.url}}" )
            .log( LoggingLevel.INFO, LOGGER, "Overdue report reminder sent => ${body}" );
    }
}
