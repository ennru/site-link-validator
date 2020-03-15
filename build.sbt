import sbt._

val AkkaVersion = "2.5.21"

lazy val `site-link-validator` = project
  .in(file("."))
  .aggregate(core, plugin)
  .settings(
    publish / skip := true
  )

lazy val core = project
  .settings(
    name := "site-link-validator",
    libraryDependencies ++= Seq(
      "org.jsoup" % "jsoup" % "1.13.1",
      "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
      "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "org.scalatest" %% "scalatest" % "3.0.6" % Test,
    )
  )

lazy val plugin = project
  .dependsOn(core)
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-site-link-validator",
    scriptedLaunchOpts += ("-Dproject.version=" + version.value),
    scriptedDependencies := {
      val p1 = (publishLocal in core).value
      val p2 = publishLocal.value
    },
    scriptedBufferLog := false
  )

inThisBuild(
  Seq(
    organization := "net.runne",
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    homepage := Some(url("https://github.com/ennru/site-link-validator")),
    scmInfo := Some(ScmInfo(url("https://github.com/ennru/site-link-validator"), "git@github.com:ennru/site-link-validator.git")),
    developers += Developer("contributors",
      "Contributors",
      "https://github.com/ennru/site-link-validator/graphs/contributors",
      url("https://github.com/ennru/site-link-validator/graphs/contributors")),
    scalafmtOnCompile := true,
    testOptions in Test += Tests.Argument("-oDF"),
    scalaVersion := "2.12.8",
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-Xlint",
      "-Ywarn-dead-code",
      "-Xfuture",
      "-target:jvm-1.8"
    )
  )
)



