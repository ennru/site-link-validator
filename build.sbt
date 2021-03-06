import sbt._

val AkkaVersion = "2.6.10"
val AkkaHttpVersion = "10.2.2"

lazy val `site-link-validator` = project.in(file(".")).aggregate(core).settings(publish / skip := true)

lazy val core = project.settings(
  name := "site-link-validator",
  libraryDependencies ++= Seq(
    "org.jsoup" % "jsoup" % "1.13.1",
    "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
    "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
    "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
    "ch.qos.logback" % "logback-classic" % "1.2.3"))

inThisBuild(
  Seq(
    organization := "net.runne",
    organizationHomepage := Some(url("https://github.com/ennru/")),
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0")),
    homepage := Some(url("https://github.com/ennru/site-link-validator")),
    scmInfo := Some(
      ScmInfo(url("https://github.com/ennru/site-link-validator"), "git@github.com:ennru/site-link-validator.git")),
    developers += Developer(
      "contributors",
      "Contributors",
      "https://github.com/ennru/site-link-validator/graphs/contributors",
      url("https://github.com/ennru/site-link-validator/graphs/contributors")),
    scalafmtOnCompile := true,
    testOptions in Test += Tests.Argument("-oDF"),
    scalaVersion := "2.13.4",
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-Xlint",
      "-Ywarn-dead-code",
      "-target:jvm-1.8")))
