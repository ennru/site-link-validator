package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }

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
      message match {
        case FileLocation(origin, file) =>
          val p = file.normalize
          if (p.toFile.exists()) {
            if (!seen.contains(p)) {
              val reader =
                context.spawnAnonymous(
                  HtmlFileReader.reader(htmlFileReaderConfig, reporter, anchorCollector, urlTester, context.self))
              val receiveCompletion =
                context.messageAdapter[HtmlFileReader.Completed.type](_ => FinishedFile)
              reader ! HtmlFileReader.FilePath(p, receiveCompletion)
              apply(htmlFileReaderConfig, reporter, anchorCollector, urlTester, outstanding + 1, seen + p)
            } else {
              Behaviors.same
            }
          } else {
            reporter ! Reporter.Missing(origin, p)
            apply(htmlFileReaderConfig, reporter, anchorCollector, urlTester, outstanding, seen + p)
          }

        case FinishedFile if outstanding == 1 =>
          Behaviors.stopped

        case FinishedFile =>
          apply(htmlFileReaderConfig, reporter, anchorCollector, urlTester, outstanding - 1, seen)
      }
    }
}
