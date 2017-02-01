/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.sbt;

import io.swagger.codegen.*;
import io.swagger.codegen.languages.AbstractJavaCodegen;
import io.swagger.models.Model;
import io.swagger.models.Operation;
import io.swagger.models.Swagger;
import io.swagger.util.Json;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public class LagomJavaCodegen extends AbstractJavaCodegen {

    private static final String TEMPLATE_DIRECTORY_NAME = "codegen-java-templates";

    public LagomJavaCodegen() {
        super();
        invokerPackage = "com.example.codegen.lagom";

        sourceFolder = "java";
        dateLibrary = "java8";

        modelTemplateFiles.put("model.mustache", ".java");
        apiTemplateFiles.put("api.mustache", ".java");
        modelDocTemplateFiles.remove("model_doc.mustache");
        apiDocTemplateFiles.remove("api_doc.mustache");


        // TODO: use immutables
        instantiationTypes.put("array", "ArrayList");
        instantiationTypes.put("map", "HashMap");


        apiPackage = "io.swagger.api";
        modelPackage = "io.swagger.model";

        apiTestTemplateFiles.clear(); // TODO: add api test template
        modelTestTemplateFiles.clear(); // TODO: add model test template

        // clear model and api doc template as this codegen
        // does not support auto-generated markdown doc at the moment
        //TODO: add doc templates

        additionalProperties.put("title", "Lagom Descriptor");

        super.embeddedTemplateDir = templateDir = TEMPLATE_DIRECTORY_NAME;

        for (int i = 0; i < cliOptions.size(); i++) {
            if (CodegenConstants.LIBRARY.equals(cliOptions.get(i).getOpt())) {
                cliOptions.remove(i);
                break;
            }
        }

        CliOption library = new CliOption(CodegenConstants.LIBRARY, "library template (sub-template) to use");
        library.setDefault(DEFAULT_LIBRARY);

        Map<String, String> supportedLibraries = new LinkedHashMap<String, String>();

        supportedLibraries.put(DEFAULT_LIBRARY, "lagomJavadslApi");
        library.setEnum(supportedLibraries);

        cliOptions.add(library);

    }

    @Override
    public CodegenOperation fromOperation(String path, String httpMethod, Operation operation, Map<String, Model> definitions, Swagger swagger) {
        CodegenOperation op = super.fromOperation(path, httpMethod, operation, definitions, swagger);

        op.path = op.path.replaceAll("\\{([^}]*)\\}", ":$1");

        return op;
    }


    @Override
    public void processOpts() {
        super.processOpts();

        // Don't need extra files provided by Lagom & Java Codegen
        supportingFiles.clear();

    }


    @Override
    public CodegenType getTag() {
        // TODO: review.
        return CodegenType.DOCUMENTATION;
    }

    @Override
    public String getName() {
        return "lagom-service-descriptor";
    }

    @Override
    public void addOperationToGroup(String tag, String resourcePath, Operation operation, CodegenOperation co, Map<String, List<CodegenOperation>> operations) {
        String basePath = resourcePath;
        if (basePath.startsWith("/")) {
            basePath = basePath.substring(1);
        }
        int pos = basePath.indexOf("/");
        if (pos > 0) {
            basePath = basePath.substring(0, pos);
        }

        if (basePath == "") {
            basePath = "default";
        } else {
            if (co.path.startsWith("/" + basePath)) {
                co.path = co.path.substring(("/" + basePath).length());
            }
            co.subresourceOperation = !co.path.isEmpty();
        }
        List<CodegenOperation> opList = operations.get(basePath);
        if (opList == null) {
            opList = new ArrayList<CodegenOperation>();
            operations.put(basePath, opList);
        }
        opList.add(co);
        co.baseName = basePath;
    }

    @Override
    public void postProcessModelProperty(CodegenModel model, CodegenProperty property) {
        super.postProcessModelProperty(model, property);
        model.imports.remove("ApiModelProperty");
        model.imports.remove("ApiModel");
        model.imports.remove("JsonSerialize");
        model.imports.remove("ToStringSerializer");
        model.imports.remove("JsonValue");
        model.imports.remove("JsonProperty");
    }

    @Override
    public void preprocessSwagger(Swagger swagger) {
        //copy input swagger to output folder 
        try {
            String swaggerJson = Json.pretty(swagger);
            FileUtils.writeStringToFile(new File(outputFolder + File.separator + "swagger.json"), swaggerJson);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e.getCause());
        }
        super.preprocessSwagger(swagger);

    }

    @Override
    public String getHelp() {
        return "Generates a Lagom Service Descriptor.";
    }

}
