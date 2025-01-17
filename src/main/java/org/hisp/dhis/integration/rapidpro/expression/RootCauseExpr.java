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
package org.hisp.dhis.integration.rapidpro.expression;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.hisp.dhis.integration.rapidpro.Dhis2RapidProException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class RootCauseExpr implements Expression
{
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public <T> T evaluate( Exchange exchange, Class<T> type )
    {
        Throwable throwable = (Throwable) exchange.getProperty( Exchange.EXCEPTION_CAUGHT );
        if ( throwable == null )
        {
            Map<String, Object> bodyAsMap = exchange.getMessage().getBody( Map.class );
            if ( bodyAsMap != null )
            {
                try
                {
                    return (T) objectMapper.writeValueAsString( bodyAsMap );
                }
                catch ( JsonProcessingException e )
                {
                    throw new Dhis2RapidProException( e );
                }
            }
            else
            {
                String bodyAsString = exchange.getMessage().getBody( String.class );
                return (T) bodyAsString;
            }
        }
        else
        {
            Throwable rootCause = NestedExceptionUtils.getRootCause( throwable );
            String message;
            if ( rootCause != null )
            {
                message = rootCause.getMessage();
            }
            else
            {
                message = throwable.getMessage();
            }
            if (message != null) {
                return (T) message;
            } else  {
                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                throwable.printStackTrace(printWriter);
                return (T) stringWriter.toString();
            }
        }
    }
}
