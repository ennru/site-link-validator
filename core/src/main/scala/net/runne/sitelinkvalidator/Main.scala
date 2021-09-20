package net.runne.sitelinkvalidator

import java.nio.file.{ Path, Paths }

import akka.actor.{ BootstrapSetup, CoordinatedShutdown }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior, Terminated }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.jdk.CollectionConverters._

object Main extends App {

  if (args.length == 1 && !args(0).startsWith("--")) {
    val f = Paths.get(args(0)).toFile
    if (f.isFile) {
      new Validator(ConfigFactory.parseFile(f)).report()
    } else {
      println(s"can't read [${f.toString}]")
    }
  } else {
    println("specify a HOCON file with a section `site-link-validator`")
  }
}

object Validator {
  sealed trait Messages
  final case class UrlReport(summary: UrlSummary.Report) extends Messages
  final case class Report(summary: Reporter.ReportSummary) extends Messages
  final case class AnchorReport(summary: AnchorValidator.Report) extends Messages

  case object ValidatorErrorShutdownReason extends CoordinatedShutdown.Reason
}

class Validator(appConfig: Config) {
  import Validator._

  val config = appConfig.withFallback(ConfigFactory.load()).resolve().getConfig("site-link-validator")

  val rootDir = {
    val dirStr = config.getString("root-dir")
    val dir = Paths.get(dirStr)
    val f = dir.toFile
    require(f.exists() && f.isDirectory, s"The `root-dir` must be an existing directory (was [$dirStr])")
    dir
  }

  val failForFailureResponse = config.getBoolean("fail-for.failure-response")
  val failForLocalFailure = config.getBoolean("fail-for.local-failure")
  val failForNonHttps = config.getBoolean("fail-for.non-https")

  val startFile = {
    val fStr = config.getString("start-file")
    val startFilePath = rootDir.resolve(fStr)
    require(startFilePath.toFile.exists(), s"The `start-file` must exist (was [${startFilePath.toString}])")
    fStr
  }

  val htmlFileReaderConfig = {
    val mappings = config.getConfigList("link-mappings").asScala.toList
    val linkMappings = mappings.map { c =>
      c.getString("prefix") -> c.getString("replace")
    }.toMap
    HtmlFileReader.Config(rootDir, linkMappings, config.getStringList("ignore-prefixes").asScala.toList)
  }

  val nonHttpsAccepted =
    (config.getStringList("non-https-accepted").asScala ++ config.getStringList("non-https-whitelist").asScala).toSeq

  def report(): Unit = report(rootDir, startFile)

  private var failFor = Vector.empty[String]

  private def print(s: String): Unit = {
    Console.print(s)
  }

  def report(dir: Path, initialFile: String): Unit = {
    val file = dir.resolve(initialFile)
    val exists = file.toFile.exists()
    require(exists, s"${file.toAbsolutePath.toString} does not exist (got dir=$dir, file=$initialFile)")
    println(
      s"checking links starting from [${file.toAbsolutePath.toString}] with root [${dir.toAbsolutePath.toString}]")
    val ignoreMissingLocalFileFilter = config.getString("ignore-missing-local-files-regex").r

    def main(): Behavior[Messages] =
      Behaviors.setup { context =>
        val reporter = context.spawn(Reporter(), "reporter")
        context.watch(reporter)
        val anchorCollector = context.spawn(AnchorValidator(), "anchorCollector")
        context.watch(anchorCollector)
        val urlTester = context.spawn(UrlTester(), "urlTester")
        context.watch(urlTester)
        val collector =
          LinkCollector.stream(htmlFileReaderConfig, reporter, anchorCollector, urlTester)(context.system)
        context.watch(collector)

        collector ! LinkCollector.FileLocation(dir, file)
        Behaviors
          .receiveMessage[Messages] {
            case UrlReport(summary) =>
              print(summary.print(rootDir, nonHttpsAccepted).mkString("\n"))
              if (failForFailureResponse && summary.hasFailures) {
                failFor = failFor :+ "Failure responses found."
              }
              if (failForNonHttps && summary.nonHttpsUrls(nonHttpsAccepted).nonEmpty) {
                failFor = failFor :+ "Non-https URLs found (configure `non-https-accepted` if needed)."
              }
              Behaviors.same

            case Report(reportSummary) =>
              print(reportSummary.report(dir, ignoreMissingLocalFileFilter).mkString("\n"))
              if (failForLocalFailure && reportSummary.hasFailures) {
                failFor = failFor :+ "Local errors encountered."
              }
              Behaviors.same

            case AnchorReport(report) =>
              print(report.report(dir, ignoreMissingLocalFileFilter).mkString("\n"))
              Behaviors.same
          }
          .receiveSignal {
            case (_, Terminated(`collector`)) =>
              val replyTo = context.messageAdapter[UrlSummary.Report](summary => UrlReport(summary))
              urlTester ! UrlTester.RequestReport(replyTo)
              Behaviors.same
            case (_, Terminated(`urlTester`)) =>
              val replyTo = context.messageAdapter[Reporter.ReportSummary](summary => Report(summary))
              reporter ! Reporter.RequestReport(replyTo)
              Behaviors.same
            case (_, Terminated(`reporter`)) =>
              val replyTo = context.messageAdapter[AnchorValidator.Report](summary => AnchorReport(summary))
              anchorCollector ! AnchorValidator.RequestReport(replyTo)
              Behaviors.same
            case (context, Terminated(`anchorCollector`)) =>
              if (failFor.nonEmpty) {
                println("\n\nFailing link validation for (details above):\n" + failFor.mkString(" * ", "\n * ", ""))
                CoordinatedShutdown(context.system).run(ValidatorErrorShutdownReason)
                Behaviors.same
              } else {
                Behaviors.stopped
              }
          }
      }

    val cld = getClass.getClassLoader
    ActorSystem(main(), "site-link-validator", BootstrapSetup().withClassloader(cld))
  }
}
