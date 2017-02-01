
lazy val dev = ProjectRef(Path.fileProperty("user.dir").getParentFile, "sbt-plugin")
lazy val plugins = (project in file(".")).dependsOn(dev)


addSbtPlugin("com.lightbend.lagom" % "lagom-sbt-plugin" % "1.3.0-SNAPSHOT")
