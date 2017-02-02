//import com.lightbend.lagom.sbt.Internal.Keys.interactionMode
//interactionMode in ThisBuild := com.lightbend.lagom.sbt.NonBlockingInteractionMode

lazy val root = (project in file("."))
  .enablePlugins(LagomJava)
  .settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.11.8",
      version := "0.1.0-SNAPSHOT"
    )),
    name := "OpenApiDescriptorGen",
    libraryDependencies += "io.swagger" % "swagger-annotations" % "1.5.12"


)


// TODO : find a way to assert a file is created.

//
//InputKey[Unit]("verifyReloadsProjA") := {
//  val expected = Def.spaceDelimited().parsed.head.toInt
//  DevModeBuild.waitForReloads((target in `a-impl`).value / "reload.log", expected)
//}
//
//InputKey[Unit]("verifyReloadsProjB") := {
//  val expected = Def.spaceDelimited().parsed.head.toInt
//  DevModeBuild.waitForReloads((target in `b-impl`).value / "reload.log", expected)
//}
//
//InputKey[Unit]("verifyNoReloadsProjC") := {
//  try {
//    val actual = IO.readLines((target in c).value / "reload.log").count(_.nonEmpty)
//    throw new RuntimeException(s"Found a reload file, but there should be none!")
//  }
//  catch {
//    case e: Exception => () // if we are here it's all good
//  }
//}
//
//InputKey[Unit]("assertRequest") := {
//  val args = Def.spaceDelimited().parsed
//  val port = args(0)
//  val path = args(1)
//  val expect = args.drop(2).mkString(" ")
//
//  DevModeBuild.waitForRequestToContain(s"http://localhost:${port}${path}", expect)
//}
