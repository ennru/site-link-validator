package net.runne.sitelinkvalidator.sbtplugin

import net.runne.sitelinkvalidator.Main

import sbt._
import sbt.Keys._
import complete.DefaultParsers._

object SiteLinkValidatorPlugin extends AutoPlugin {

  object autoImport extends SiteLinkValidatorKeys

  import autoImport._

  override def trigger = AllRequirements

  override def projectSettings: Seq[Setting[_]] = pluginSettings(Compile)

  def pluginSettings(config: Configuration): Seq[Setting[_]] =
    globalSettings ++ inConfig(config)(
      Seq(
        siteLinkValidatorSourceDir := (Compile / doc / target).value,
        siteLinkValidatorEntryFile := "index.html",
        siteLinkValidatorMain := {
          val (dir, file) = spaceDelimited("<dir> <file>").parsed match {
            case Seq(dir, file) => (dir, file)
            case _ =>
              sys.error("Please specify the <dir> and <file> tags as the arguments file the task.")
          }
          Main.main(Array(dir, file))
        },
        siteLinkValidatorTextReport := Def.task {
            val dir = siteLinkValidatorSourceDir.value
            val file = siteLinkValidatorEntryFile.value
            Main.report(dir.toPath, file)
            streams.value.log.info("Created")
          },
        aggregate in siteLinkValidatorMain := false))

  override def globalSettings: Seq[Setting[_]] =
    Seq()
}
