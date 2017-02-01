/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.sbt

import com.lightbend.lagom.sbt.LagomImport._
import io.swagger.codegen._
import io.swagger.codegen.config.CodegenConfigurator
import sbt.Keys._
import sbt._
import scala.collection.JavaConverters._

object LagomOpenApiGenerator {

  def lagomOpenAPIGenerateDescriptorTask = Def.task {
    val packageName = organization.value
    val specFiles: Seq[File] = (sources in lagomOpenAPIGenerateDescriptor).value
    val outputDirectory = (target in lagomOpenAPIGenerateDescriptor).value

    specFiles.flatMap { specFile =>
      val opts = new CodegenConfigurator()
        .setLang("com.lightbend.lagom.sbt.LagomJavaCodegen")
        .setInputSpec(specFile.getAbsolutePath)
        .setOutputDir(outputDirectory.getAbsolutePath)
        .setApiPackage(packageName + ".api")
        .setInvokerPackage(packageName + ".invoker")
        .setModelPackage(packageName + ".model")

      new DefaultGenerator().opts(opts.toClientOptInput).generate().asScala

    }
  }
}

