/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.olingo.client.core.communication.header;

import org.apache.commons.io.IOUtils;
import org.apache.http.StatusLine;
import org.apache.olingo.client.api.ODataClient;
import org.apache.olingo.client.api.communication.ODataClientErrorException;
import org.apache.olingo.client.api.communication.ODataServerErrorException;
import org.apache.olingo.client.api.serialization.ODataDeserializerException;
import org.apache.olingo.client.api.serialization.ODataReader;
import org.apache.olingo.commons.api.ex.ODataError;
import org.apache.olingo.commons.api.ex.ODataRuntimeException;
import org.apache.olingo.commons.api.format.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ODataErrorResponseChecker {

    protected static final Logger LOG = LoggerFactory.getLogger(ODataErrorResponseChecker.class);

    private static ODataError getGenericError(StatusLine statusLine, String errorMessage) {
        final ODataError error = new ODataError();
        error.setCode(String.valueOf(statusLine.getStatusCode()));
        if (errorMessage == null || errorMessage.isEmpty()) {
            error.setMessage(statusLine.getReasonPhrase());
        } else {
            error.setMessage(errorMessage);
        }
        error.setTarget(statusLine.getReasonPhrase());
        return error;
    }

    public static ODataRuntimeException checkResponse(
            final ODataClient odataClient, final StatusLine statusLine, final InputStream entity,
            final String accept) {

        ODataRuntimeException result;

        if (entity == null) {
            result = new ODataClientErrorException(statusLine);
        } else {
            final ContentType contentType = accept.contains("xml") ? ContentType.APPLICATION_ATOM_XML
                    : ContentType.JSON;
            String errorMessage = readErrorString(entity);

            ODataError error = null;

            if (!accept.contains("text/plain")) {
                try {
                    ODataReader reader = odataClient.getReader();
                    error = parseError(reader, contentType, errorMessage);
                    if (error == null) {
                        // try parse with different content type
                        if (ContentType.APPLICATION_ATOM_XML.equals(contentType)) {
                            error = parseError(reader, ContentType.JSON, errorMessage);
                        } else {
                            error = parseError(reader, ContentType.APPLICATION_ATOM_XML, errorMessage);
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Unable to deserialize error response: " + e, e);
                }
            }

            if (error == null || error.getCode() == null) {
                error = getGenericError(statusLine, errorMessage);
            }

            if (statusLine.getStatusCode() >= 500
                    && (error.getCode() == null || error.getCode().isEmpty())
                    && (error.getDetails() == null || error.getDetails().isEmpty())
                    && (error.getInnerError() == null || error.getInnerError().size() == 0)) {
                result = new ODataServerErrorException(statusLine);
            } else {
                result = new ODataClientErrorException(statusLine, error);
            }
        }

        return result;
    }

    private static ODataError parseError(ODataReader reader, ContentType contentType, String errorMessage) {
        try {
            ODataError error = reader.readError(new ByteArrayInputStream(
                    errorMessage.getBytes(StandardCharsets.UTF_8)), contentType);
            if (error != null) {
                Map<String, String> innerError = error.getInnerError();
                if (innerError != null) {
                    if (innerError.get("internalexception") != null) {
                        error.setMessage(error.getMessage() + innerError.get("internalexception"));
                    } else {
                        error.setMessage(error.getMessage() + innerError.get("message"));
                    }
                }
            }
            LOG.debug("Deserialized error: {} for: {} contentType: {}", error, errorMessage, contentType);
            return error;
        } catch (ODataDeserializerException e) {
            LOG.debug("Error deserializing error response", e);
            return null;
        }
    }

    private static String readErrorString(InputStream entity) {
        try {
            return IOUtils.toString(entity, StandardCharsets.UTF_8);
        } catch (IOException e) {
            String message = "Unable to deserialize error response: " + e;
            LOG.warn(message, e);
            return message;
        }
    }

    private ODataErrorResponseChecker() {
        // private constructor for static utility class
    }
}
