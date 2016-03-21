package controllers

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.google.inject.Inject
import com.google.inject.name.Named
import controllers.CounterActor.{GetAllUsers, GetUserCount, GetUserCountByKind}
import controllers.geoip.GeoIPActor.UserInfo
import controllers.geoip.GeoIPWebSocket
import models._
import play.api.Configuration
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json._
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.duration._

class API @Inject() (database: Database, status: Status,
                     @Named("geoip-actor") geoIPActor: ActorRef,
                     @Named("counter-actor") counterActor: ActorRef,
                     config: Configuration) extends Controller {

  private[this] val API_KEY = Option(System.getenv("API_KEY")) getOrElse "apikey"
  private[this] val counterTimeout = Duration(config.getMilliseconds("count-request-timeout").get, TimeUnit.MILLISECONDS)

  private[this] def requestIP(request: Request[AnyContent]): String =
    request.headers.get("X-Forwarded-For").fold(request.remoteAddress)(_.split(", ").last)

  def getStatus(version_code: Int, version_name: String) = Action { request =>
    val (kind, stat) = status.status(version_code, version_name)
    val locale = request.getQueryString("locale").getOrElse("")
    geoIPActor ! UserInfo(requestIP(request), locale, kind)
    Counters.count(kind)
    stat map { data =>
      Ok(toJson(data))
    } getOrElse Ok(toJson(Map("status" -> "up-to-date")))
  }

  private def checkKey(params: Map[String, Seq[String]])(body: Map[String, String] => Result) =
    if (params.get("key").contains(Seq(API_KEY)))
      body(params.collect { case (k, Seq(v)) => k -> v })
    else
      Forbidden("wrong or missing key")

  def update(kind: String) = Action { request =>
    val params = request.body.asFormUrlEncoded.getOrElse[Map[String, Seq[String]]](Map.empty)
    checkKey(params) { params =>
      BuildKind.fromName.get(kind) match {
        case Some(k) =>
          (for (versionCode <- params.get("version_code");
                versionName <- params.get("version_name"))
          yield {
            database.updateVersionFor(Version(k, versionName, versionCode.toInt))
            Counters.reset(k)
            Ok("updated")
          }) getOrElse BadRequest("invalid parameters")
        case None =>
          BadRequest("unknown kind")
      }
    }
  }

  def delete(kind: String, key: String) = Action {
    if (key == API_KEY)
      BuildKind.fromName.get(kind) match {
        case Some(k) =>
          database.deleteKind(k)
          Ok("deleted")
        case None    =>
          BadRequest("unknown kind")
      }
    else
      Forbidden("wrong key")
  }

  def updateMessage() = Action { request =>
    val params = request.body.asFormUrlEncoded.getOrElse[Map[String, Seq[String]]](Map.empty)
    checkKey(params) { params =>
      params.get("message") match {
        case Some(message) =>
          database.updateMessage(Message(message, params.get("message_id"), params.get("icon"), params.get("url")))
          Ok("updated")
        case None =>
          BadRequest("invalid parameters")
      }
    }
  }

  def deleteMessage(key: String) = Action {
    if (key == API_KEY) {
      database.deleteMessage()
      Ok("deleted")
    } else
      Forbidden
  }

  def countByKind = Action.async {
    counterActor.ask(GetUserCountByKind)(counterTimeout).mapTo[Map[BuildKind, Long]].map { counters =>
      Ok(JsObject(counters.map { case (kind, count) =>
        database.latestVersionFor(kind) match {
          case Some(version) if version.code != 0 =>
            kind.name -> Json.obj("count" -> count, "versionCode" -> version.code, "versionName" -> version.name)
          case _ =>
            kind.name -> Json.obj("count" -> count)
        }
      }))
    }
  }

  def recentLocations = Action.async { request =>
    val limit = request.queryString.get("limit").map(_.head.toInt)
    counterActor.ask(GetAllUsers(true, limit))(counterTimeout).mapTo[List[User]].map { users =>
      Ok(JsArray(users.map(_.toJson)))
    }
  }

  private[this] val maxBatchInterval = Duration(config.getMilliseconds("geoip.client.max-batch-interval").get, TimeUnit.MILLISECONDS)
  private[this] val maxBatchSize = config.getInt("geoip.client.max-batch-size").get

  def locations = WebSocket.accept[JsValue, JsValue] { request =>
    val limit = request.queryString.get("initial").map(_.head.toInt)
    // Start with the list of current users, then group positions together.
    // The total number of users will also be added with every message.
    // 5 batches are queued if backpressured by the websocket, then the whole
    // buffer is dropped as the client obviously cannot keep up.
    val source = Source.actorPublisher[User](Props(new GeoIPWebSocket(geoIPActor)))
      .groupedWithin(maxBatchSize, maxBatchInterval)
      .prepend(Source.fromFuture(counterActor.ask(GetAllUsers(true, limit))(counterTimeout).mapTo[List[User]]))
      .mapAsync(1) { g =>
        counterActor.ask(GetUserCount)(counterTimeout).mapTo[Long].map { count =>
          Json.obj("clients" -> g.map(_.toJson), "active" -> count)
        }
      }
      .buffer(5, OverflowStrategy.dropBuffer)
    Flow.fromSinkAndSource(Sink.ignore, source)
  }

}
