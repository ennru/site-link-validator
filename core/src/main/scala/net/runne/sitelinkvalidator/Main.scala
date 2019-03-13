package net.runne.sitelinkvalidator

import java.nio.file.{Path, Paths}

import akka.NotUsed
import akka.actor.BootstrapSetup
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior, Terminated}

object Main extends App {

//  require(args.length == 2, "expecting <dir> and <file> as arguments")
//  val dir = Paths.get(args(0))
//  val initialFile = args(1)

    val dir = Paths.get("/Users/enno/dev/alpakka/docs/target/site/")
    val initialFile = "docs/alpakka/snapshot/index.html"
  //  val dir = Paths.get("/Users/enno/dev/akka-docs-copy/main")
  //  val initialFile = "index.html"

  report(dir, initialFile)

  trait Messages

  case class UrlReport(summary: UrlTester.ReportSummary) extends Messages
  case class Report(summary: Reporter.ReportSummary) extends Messages
  case class AnchorReport(summary: AnchorValidator.Report) extends Messages

  def report(dir: Path, initialFile: String): Unit = {
    val file = dir.resolve(initialFile)
    val exists = file.toFile.exists()
    require(
      exists,
      s"${file.toAbsolutePath.toString} does not exist (got dir=$dir, file=$initialFile)")

    def main(): Behavior[Messages] =
      Behaviors.setup { context ⇒
        val ignoreFilter = "^(api/.*)".r
        val reporter = context.spawn(Reporter(), "reporter")
        context.watch(reporter)
        val anchorCollector =
          context.spawn(AnchorValidator(), "anchorCollector")
        context.watch(anchorCollector)
        val urlTester = context.spawn(UrlTester(), "urlTester")
        context.watch(urlTester)
        val collector =
          context.spawn(LinkCollector(reporter, anchorCollector, urlTester),
            "collector")
        context.watch(collector)

        collector ! LinkCollector.FileLocation(dir, file)
        Behaviors
          .receiveMessage[Messages] {
          case UrlReport(summary) =>
            print(summary.print().mkString("\n"))
            urlTester ! UrlTester.Shutdown
            Behaviors.same

          case Report(reportSummary) =>
            println(reportSummary.errorReport(dir).mkString("\n"))
            println(reportSummary.missingReport(dir, ignoreFilter).mkString("\n"))
            println(reportSummary.urlFailureReport(dir).mkString("\n"))
            Behaviors.same

          case AnchorReport(report) =>
            report.report(dir, ignoreFilter)
            Behaviors.same
        }
          .receiveSignal {
            case (_, Terminated(`collector`)) =>
              val replyTo = context.messageAdapter[UrlTester.ReportSummary](summary => UrlReport(summary))
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
            case (_, Terminated(`anchorCollector`)) =>
              Behaviors.stopped
          }
      }

    val cld = getClass.getClassLoader
    ActorSystem(main, "site-link-validator", BootstrapSetup().withClassloader(cld))
  }
}
