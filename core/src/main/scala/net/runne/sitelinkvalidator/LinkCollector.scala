package net.runne.sitelinkvalidator

import java.nio.file.{ Path, Paths }

import akka.NotUsed
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Flow, Sink }
import akka.stream.typed.scaladsl.ActorSource

import scala.concurrent.Promise
import scala.concurrent.duration._

object LinkCollector {

  final case class FileLocation(origin: Path, file: Path)

  def stream(
      htmlFileReaderConfig: HtmlFileReader.Config,
      reporter: ActorRef[Reporter.Messages],
      anchorCollector: ActorRef[AnchorValidator.Messages],
      urlTester: ActorRef[UrlTester.Messages])(implicit system: ActorSystem[_]): ActorRef[FileLocation] = {
    implicit val ec = system.executionContext

    def unique[T]: Flow[T, T, NotUsed] = Flow[T].statefulMapConcat { () =>
      var seen: Set[T] = Set.empty
      value =>
        if (seen.contains(value)) List()
        else {
          seen = seen + value
          List(value)
        }
    }

    val self = Promise[ActorRef[FileLocation]]
    val collector = ActorSource
      .actorRef[FileLocation](
        completionMatcher = PartialFunction.empty,
        failureMatcher = PartialFunction.empty,
        5000,
        OverflowStrategy.fail)
      // termination of this stream signals end of processing
      .idleTimeout(500.millis)
      .map {
        case FileLocation(_, file) =>
          findHtml(file.normalize)
      }
      .via(unique)
      .filter(_.toFile.isFile)
      .mapAsync(100) { file =>
        self.future.map { linkCollector =>
          HtmlFileReader.findLinks(htmlFileReaderConfig, reporter, anchorCollector, urlTester, linkCollector, file)
        }
      }
      .to(Sink.ignore)
      .run()
    self.success(collector)
    collector
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
