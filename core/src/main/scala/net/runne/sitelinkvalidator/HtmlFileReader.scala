package net.runne.sitelinkvalidator

import java.nio.file.{Path, Paths}

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.alpakka.xml.StartElement
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.scaladsl.{FileIO, Sink}
import akka.stream.typed.scaladsl.ActorMaterializer

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{Failure, Success}

object HtmlFileReader {

  sealed trait Messages

  final case class FilePath(file: Path, replyTo: ActorRef[Completed.type])
      extends Messages

  case object Completed extends Messages

  sealed trait FoundData

  final case class Link(s: String) extends FoundData

  final case class Anchor(s: String) extends FoundData

  val linkMappings: Map[String, String] = Map(
    "http:/api/" -> "/Users/enno/dev/alpakka/docs/target/site/api/"
  )

  def reader(
      reporter: ActorRef[Reporter.Messages],
      anchorValidator: ActorRef[AnchorValidator.Messages],
      urlTester: ActorRef[UrlTester.Messages],
      linkCollector: ActorRef[LinkCollector.Messages]): Behavior[Messages] =
    Behaviors.receive {
      def checkLocalLink(file: Path, link: String) = {
        val (path, anchor) = splitLinkAnchor(link)
        if (path.nonEmpty) {
          val f = file.getParent.resolve(path).normalize
          linkCollector ! LinkCollector.FileLocation(file, f)
          if (anchor.nonEmpty) {
            anchorValidator ! AnchorValidator.Link(file, f, anchor)
          }
        } else if (anchor.nonEmpty) {
          anchorValidator ! AnchorValidator.Link(file, file, anchor)
        }
      }

      (context, message) =>
        message match {
          case FilePath(file, replyTo) =>
            implicit val mat = ActorMaterializer()(context.system)
            val fileReader: Future[Done] = FileIO
              .fromPath(file)
              .via(XmlParsing.parser(ignoreInvalidChars = true))
              .mapConcat {
                case s: StartElement if s.localName == "a" =>
                  s.attributes
                    .get("href")
                    .map(Link)
                    .toIndexedSeq ++ s.attributes
                    .get("name")
                    .map(Anchor)
                    .toIndexedSeq ++ s.attributes
                    .get("id")
                    .map(Anchor)
                    .toIndexedSeq
                case s: StartElement if s.localName == "link" =>
                  s.attributes.get("href").map(Link).toIndexedSeq
                case s: StartElement if s.localName == "img" =>
                  s.attributes.get("src").map(Link).toIndexedSeq
                case s: StartElement
                    if s.localName == "script" && s.attributes.contains(
                      "src") =>
                  s.attributes.get("src").map(Link).toIndexedSeq
                case _ =>
                  immutable.Seq.empty
              }
              .runWith(Sink.foreach {
                case Link(link) =>
                  if (link.startsWith("http")) {
                    linkMappings
                      .collectFirst {
                        case (prefix, path) if link.startsWith(prefix) =>
                          (prefix, path)
                      }
                      .fold {
                        urlTester ! UrlTester.Url(file, link)
                      } {
                        case (prefix, path) =>
                          val patchedLink = link.substring(prefix.length)
                          checkLocalLink(file, path + patchedLink)
                      }
                  } else if (link.contains(".html")) {
                    checkLocalLink(file, link)
                  }
                case Anchor(name) =>
                  anchorValidator ! AnchorValidator.Anchor(file, name)
              })
            fileReader.onComplete {
              case Success(_) =>
                reporter ! Reporter.FileChecked(file)
                replyTo ! Completed
                context.self ! Completed
              case Failure(e) =>
                reporter ! Reporter.FileErrored(file, e)
                replyTo ! Completed
                context.self ! Completed
            }(context.system.executionContext)
            Behaviors.same

          case Completed =>
            Behaviors.stopped
        }
    }

  private def splitLinkAnchor(link: String) = {
    val p = link.indexOf('#')
    if (p == -1) {
      (link, "")
    } else {
      (link.substring(0, p), link.substring(p + 1))
    }

  }
}
