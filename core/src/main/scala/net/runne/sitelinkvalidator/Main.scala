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

  report(dir, initialFile)

  def report(dir: Path, initialFile: String): Unit = {
    val file = dir.resolve(initialFile)
    val exists = file.toFile.exists()
    require(exists, s"${file.toAbsolutePath.toString} does not exist (got dir=$dir, file=$initialFile)")

    val main: Behavior[NotUsed] =
      Behaviors.setup { context ⇒
        val ignoreFilter = "^(api/.*)".r
        val reporter = context.spawn(Reporter(dir), "reporter")
        context.watch(reporter)
        val anchorCollector = context.spawn(AnchorValidator(dir), "anchorCollector")
        context.watch(anchorCollector)
        val urlTester = context.spawn(UrlTester(reporter), "urlTester")
        context.watch(urlTester)
        val collector = context.spawn(LinkCollector(reporter, anchorCollector, urlTester), "collector")
        context.watch(collector)

        collector ! LinkCollector.FileLocation(dir, file)
        Behaviors
          .receiveSignal {
            case (_, Terminated(`collector`)) =>
              urlTester ! UrlTester.Shutdown
              Behaviors.same
            case (_, Terminated(`urlTester`)) =>
              reporter ! Reporter.Report(ignoreFilter)
              Behaviors.same
            case (_, Terminated(`reporter`)) =>
              anchorCollector ! AnchorValidator.Check(ignoreFilter)
              Behaviors.same
            case (_, Terminated(`anchorCollector`)) =>
              Behaviors.stopped
          }
      }

    val cld = getClass.getClassLoader
    ActorSystem(main, "bld", BootstrapSetup().withClassloader(cld))
  }
}
