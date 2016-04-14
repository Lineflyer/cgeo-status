package models

import com.sanoma.cda.geo.Point
import play.api.libs.json.Json

case class User(kind: BuildKind, locale: String, coords: Option[Point], versionName: String, versionCode: Int, ip: String,
    timestamp: Long = System.currentTimeMillis()) {

  lazy val toJson = {
    val base = Json.obj("kind" → kind.name)
    coords.fold(base)(p ⇒ base ++ Json.obj("latitude" → p.latitude, "longitude" → p.longitude))
  }
}
