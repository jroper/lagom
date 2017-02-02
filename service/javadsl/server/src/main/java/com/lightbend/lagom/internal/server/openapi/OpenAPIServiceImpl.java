/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.internal.server.openapi;

import akka.NotUsed;
import com.lightbend.lagom.javadsl.api.ServiceCall;
import com.lightbend.lagom.javadsl.server.openapi.OpenAPIService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


public class OpenAPIServiceImpl implements OpenAPIService {
    @Override
    public ServiceCall<NotUsed, String> downloadSpec(String specFileName) {
        return req -> {
            CompletableFuture cf = new CompletableFuture();
            // TODO: prevent path traversal
            // TODO: validate input to only json/yaml
            // TODO: execute read in a separate thread pool
            try (BufferedReader buffer =
                         new BufferedReader(
                                 new InputStreamReader(OpenAPIServiceImpl.class.getClassLoader().getResourceAsStream(specFileName)))) {
                cf.complete(buffer.lines().collect(Collectors.joining("\n")));
            } catch (IOException ioe) {
                cf.completeExceptionally(ioe);
            }
            // TODO: fix Content-Type
            return cf;
        };
    }
}
