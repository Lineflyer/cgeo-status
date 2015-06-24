lazy val cgeoStatus = (project in file(".")).enablePlugins(play.PlayScala).settings(
  name := "cgeo-status",
  version := "1.1",
  scalaVersion := "2.11.7",
  libraryDependencies ++=  Seq(filters, "org.mongodb" %% "casbah-core" % "2.8.0")
)
