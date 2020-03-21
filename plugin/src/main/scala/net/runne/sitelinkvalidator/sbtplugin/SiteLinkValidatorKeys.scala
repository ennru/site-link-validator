package net.runne.sitelinkvalidator.sbtplugin

import sbt.{ inputKey, settingKey, taskKey, File }

trait SiteLinkValidatorKeys {
  val siteLinkValidatorSourceDir =
    settingKey[File]("Root dir for site to scan.")
  val siteLinkValidatorEntryFile =
    settingKey[String](
      "Path to initial file relative to `siteLinkValidatorSourceDir` as entry point for site link validation.")
  val siteLinkValidatorTextReport = taskKey[Unit]("Run Forrest, Run!")
  val siteLinkValidatorMain = inputKey[Unit]("Run Forrest, Run!")
}
