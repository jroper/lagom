/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.javadsl.server.openapi;

import akka.NotUsed;
import com.lightbend.lagom.javadsl.api.Descriptor;
import com.lightbend.lagom.javadsl.api.Service;

import static com.lightbend.lagom.javadsl.api.Service.*;

import com.lightbend.lagom.javadsl.api.ServiceCall;


public interface OpenAPIService extends Service {

    ServiceCall<NotUsed, String> downloadSpec(String specFileName);

    @Override
    default Descriptor descriptor() {
        // @formatter:off
        return named("/openapi").withCalls(
                pathCall("/openapi/:specFile", this::downloadSpec)
        ).withAutoAcl(true);

    }
}
