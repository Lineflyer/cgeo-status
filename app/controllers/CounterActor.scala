package controllers

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Terminated}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.google.inject.Inject
import com.google.inject.name.Named
import models.{BuildKind, Status, User}
import play.api.{Configuration, Logger}

import scala.concurrent.duration._

class CounterActor @Inject() (config: Configuration, status: Status, @Named("geoip-actor") geoIPActor: ActorRef) extends Actor {

  import CounterActor._

  private[this] implicit val dispatcher = context.system.dispatcher
  private[this] val geoIPTimeout: Timeout = Duration(config.getMilliseconds("geoip.resolution-timeout").get, TimeUnit.MILLISECONDS)
  private[this] var users: List[User] = Nil
  private[this] var clients: Set[ActorRef] = Set()

  private[this] def trimOld() = {
    val trimTimestamp = System.currentTimeMillis() - updatePeriodMs
    users = users.dropWhile(_.timestamp < trimTimestamp)
  }

  private[this] def factor: Double =
    users.headOption.fold(1.0) { oldest =>
      val range = System.currentTimeMillis() - oldest.timestamp
      if (range <= 0) 1.0 else updatePeriodMs.toDouble / range
    }

  private[this] def adjust[T](data: Map[T, Long]): Map[T, Long] = {
    val f = factor
    data.mapValues(count => (count * f).round)
  }

  def receive = {

    case user: User =>
      // Try to add GeoIP information before adding this user to the users count.
      // If the GeoIP information is not available in a reasonable time, register
      // the user with an unknown location.
      pipe(geoIPActor.ask(user)(geoIPTimeout).mapTo[User].map(WithGeoIP).recover {
        case t: Throwable =>
          Logger.error(s"cannot resolve geoip for ${user.ip}", t)
          WithGeoIP(user)
      }).to(self)

    case WithGeoIP(user) =>
      // GeoIP received (possibly empty), send it to registered websocket clients
      // if we have some.
      trimOld()
      users :+= user
      if (user.coords.isDefined)
        clients.foreach(_ ! user)

    case Reset =>
      // Recompute the build kind used by the users, as the database has just been
      // updated. We might still get some bogus information from users whose geoips
      // are currently being resolved. However, this is not a big deal if some statistics
      // are off by a few units.
      trimOld()
      users = users.map(user => user.copy(kind = status.status(user.versionCode, user.versionName)._1))

    case Register(actorRef) =>
      // Register a websocket client and watch it to remove it when the websocket is closed.
      clients += actorRef
      context.watch(actorRef)

    case Terminated(actorRef) =>
      // A websocket has been closed
      clients -= actorRef

    case GetAllUsers(withCoordinates, limit) =>
      // Retrieve all users
      trimOld()
      val filtered = if (withCoordinates) users.filter(_.coords.isDefined) else users
      sender ! limit.fold(filtered)(filtered.takeRight)

    case GetUserCount =>
      // Count users
      trimOld()
      sender ! (users.size * factor).round

    case GetUserCountByKind =>
      // Sort users by version kind. This could be enhanced to include more statistics,
      // for example the language used in the application.
      trimOld()
      var userCount: Map[BuildKind, Long] = BuildKind.kinds.map(_ -> 0L).toMap
      for (user <- users)
        userCount += user.kind -> (userCount(user.kind) + 1)
      val adjusted = adjust(userCount)
      sender ! BuildKind.kinds.map(k => k -> adjusted(k))

  }

}

object CounterActor {

  case class Register(actorRef: ActorRef)
  case class GetAllUsers(withCoordinates: Boolean, limit: Option[Int])
  case object GetUserCount
  case object GetUserCountByKind
  case object Reset
  private case class WithGeoIP(user: User)

  // Updates from users are done every 30 minutes
  private val updatePeriodMs = 30 * 60 * 1000
}
