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
import org.apache.camel.processor.aggregate.GroupedBodyAggregationStrategy;
import org.hisp.dhis.api.model.v40_0.DataSet;
import org.hisp.dhis.integration.rapidpro.CompleteDataSetRegistrationFunction;
import org.hisp.dhis.integration.rapidpro.aggregationStrategy.ContactOrgUnitIdAggrStrategy;
import org.hisp.dhis.integration.rapidpro.expression.RootCauseExpr;
import org.hisp.dhis.integration.rapidpro.processor.CurrentPeriodCalculator;
import org.hisp.dhis.integration.rapidpro.processor.IdSchemeQueryParamSetter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class DeliverReportRouteBuilder extends AbstractRouteBuilder
{
    @Autowired
    private CurrentPeriodCalculator currentPeriodCalculator;

    @Autowired
    private RootCauseExpr rootCauseExpr;

    @Autowired
    private IdSchemeQueryParamSetter idSchemeQueryParamSetter;

    @Autowired
    private ContactOrgUnitIdAggrStrategy contactOrgUnitIdAggrStrategy;

    @Autowired
    private CompleteDataSetRegistrationFunction completeDataSetRegistrationFunction;

    @Override
    protected void doConfigure()
    {
        from( "quartz://dhis2AggregateReports?cron={{report.delivery.schedule.expression}}" )
            .routeId( "Schedule Report Delivery" )
            .precondition( "'{{report.delivery.schedule.expression:}}' != ''" )
            .pollEnrich( "jms:queue:dhis2AggregateReports" )
            .to( "direct:deliverReport" );

        from( "jms:queue:dhis2AggregateReports" )
            .routeId( "Consume Report" )
            .precondition( "'{{report.delivery.schedule.expression:}}' == ''" )
            .kamelet( "hie-create-replay-checkpoint-action" )
            .to( "direct:deliverReport" );

        from( "direct:deliverReport" )
            .routeId( "Deliver Report" )
            .to( "direct:transformReport" )
            .to( "direct:transmitReport" );

        from( "direct:transformReport" )
            .routeId( "Transform Report" )
            .streamCache("true")
            .setHeader( "originalPayload", simple( "${body}" ) )
            .unmarshal().json()
            .choice().when( header( "reportPeriodOffset" ).isNull() )
                .setHeader( "reportPeriodOffset", constant( -1 ) )
            .end()
            .enrich()
            .simple( "dhis2://get/resource?path=dataElements&filter=dataSetElements.dataSet.code:eq:${headers['dataSetCode']}&fields=code&client=#dhis2Client" )
            .aggregationStrategy( ( oldExchange, newExchange ) -> {
                oldExchange.getMessage().setHeader( "dataElementCodes",
                    jsonpath( "$.dataElements..code" ).evaluate( newExchange, List.class ) );
                return oldExchange;
            } )
            .choice().when( header( "orgUnitId" ).isNull() )
                .setHeader( "uuid", simple( "${body[contact][uuid]}" ) )
                .enrich().simple( "kamelet:hie-rapidpro-get-contacts-sink?rapidProApiToken={{rapidpro.api.token}}&rapidProApiUrl={{rapidpro.api.url}}" )
                    .aggregationStrategy( contactOrgUnitIdAggrStrategy )
                .end()
            .end()
            .enrich( "direct:computePeriod", ( oldExchange, newExchange ) -> {
                oldExchange.getMessage().setHeader( "period", newExchange.getMessage().getBody() );
                return oldExchange;
            } )
            .transform( datasonnet( "resource:classpath:dataValueSet.ds", Map.class, "application/x-java-object",
                "application/x-java-object" ) )
            .process( idSchemeQueryParamSetter )
            .marshal().json().transform().body( String.class );

        from( "direct:transmitReport" )
            .routeId( "Transmit Report" )
            .log( LoggingLevel.INFO, LOGGER, "Saving data value set => ${body}" )
            .setHeader( "dhisRequest", simple( "${body}" ) )
            .toD( "dhis2://post/resource?path=dataValueSets&inBody=resource&client=#dhis2Client" )
            .setBody( (Function<Exchange, Object>) exchange -> exchange.getMessage().getBody( String.class ) )
            .setHeader( "dhisResponse", simple( "${body}" ) )
            .unmarshal().json()
            .choice()
            .when( simple( "${body['status']} == 'SUCCESS' || ${body['status']} == 'OK'" ) )
                .to( "direct:completeDataSetRegistration" )
            .otherwise()
                .setHeader( "errorMessage", simple( "Import error from DHIS2 while saving data value set => ${body}" ) )
                .log( LoggingLevel.ERROR, LOGGER,  "${header.errorMessage}"  )
                .kamelet( "hie-fail-replay-checkpoint-action" )
            .end();

        from( "direct:computePeriod" )
            .routeId( "Compute Period" )
            .toD( "dhis2://get/collection?path=dataSets&arrayName=dataSets&filter=code:eq:${headers['dataSetCode']}&fields=periodType&client=#dhis2Client" )
            .split().body().aggregationStrategy( new GroupedBodyAggregationStrategy() )
                .convertBodyTo( DataSet.class )
            .end()
            .process( currentPeriodCalculator );

        from( "direct:completeDataSetRegistration" )
            .setBody( completeDataSetRegistrationFunction )
            .toD( "dhis2://post/resource?path=completeDataSetRegistrations&inBody=resource&client=#dhis2Client" )
            .unmarshal().json()
            .choice()
            .when( simple( "${body['status']} == 'SUCCESS' || ${body['status']} == 'OK'" ) )
                .setHeader( "rapidProPayload", header( "originalPayload" ) )
                .setBody( simple( "${properties:report.success.log.insert.{{spring.sql.init.platform}}}" ) )
                .to( "jdbc:dataSource?useHeadersAsParameters=true" )
            .otherwise()
                .setHeader( "errorMessage", simple( "Error from DHIS2 while completing data set registration => ${body}" ) )
                .log( LoggingLevel.ERROR, LOGGER, "${header.errorMessage}" )
                .kamelet( "hie-fail-replay-checkpoint-action" )
            .end();
    }
}
