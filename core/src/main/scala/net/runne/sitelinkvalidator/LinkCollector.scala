package net.runne.sitelinkvalidator

import java.nio.file.{ Path, Paths }

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }

import scala.concurrent.Future

object LinkCollector {

  trait Messages

  final case class FileLocation(origin: Path, file: Path) extends Messages

  case object FinishedFile extends Messages

  def apply(
      htmlFileReaderConfig: HtmlFileReader.Config,
      reporter: ActorRef[Reporter.Messages],
      anchorCollector: ActorRef[AnchorValidator.Messages],
      urlTester: ActorRef[UrlTester.Messages]): Behavior[Messages] =
    apply(htmlFileReaderConfig, reporter, anchorCollector, urlTester, outstanding = 0, seen = Set.empty)

  private def apply(
      htmlFileReaderConfig: HtmlFileReader.Config,
      reporter: ActorRef[Reporter.Messages],
      anchorCollector: ActorRef[AnchorValidator.Messages],
      urlTester: ActorRef[UrlTester.Messages],
      outstanding: Int,
      seen: Set[Path]): Behavior[Messages] =
    Behaviors.receive { (context, message) =>
      import context.executionContext
      message match {
        case FileLocation(origin, file) =>
          if (seen.contains(file)) {
            Behaviors.same
          } else {
            val p = findHtml(file.normalize)
            if (seen.contains(p)) {
              Behaviors.same
            } else {
              if (p.toFile.exists()) {
                context.pipeToSelf(Future {
                  HtmlFileReader.findLinks(htmlFileReaderConfig, reporter, anchorCollector, urlTester, context.self, p)
                }) { _ =>
                  FinishedFile
                }
                apply(htmlFileReaderConfig, reporter, anchorCollector, urlTester, outstanding + 1, seen + p + file)
              } else {
                reporter ! Reporter.Missing(origin, p)
                apply(htmlFileReaderConfig, reporter, anchorCollector, urlTester, outstanding, seen + p + file)
              }
            }
          }

        case FinishedFile if outstanding == 1 =>
          Behaviors.stopped

        case FinishedFile =>
          apply(htmlFileReaderConfig, reporter, anchorCollector, urlTester, outstanding - 1, seen)
      }
    }

  private def findHtml(p: Path) = {
    if (p.toFile.exists()) {
      if (p.toFile.isFile) p
      else {
        val index = p.resolve("index.html")
        if (p.toFile.isDirectory && index.toFile.isFile) index
        else p
      }
    } else {
      val p2 = Paths.get(p.toString + ".html")
      if (p2.toFile.isFile) p2
      else p
    }
  }
}
