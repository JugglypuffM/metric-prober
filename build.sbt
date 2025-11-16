ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .settings(
    name := "metric-prober"
  )

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "3.5.4",
  "co.fs2" %% "fs2-core" % "3.10.2",
  "org.http4s" %% "http4s-ember-server" % "0.23.25",
  "org.http4s" %% "http4s-ember-client" % "0.23.25",
  "org.http4s" %% "http4s-dsl" % "0.23.25",
  "io.prometheus" % "simpleclient" % "0.16.0",
  "io.prometheus" % "simpleclient_common" % "0.16.0",
  "org.http4s" %% "http4s-circe" % "0.23.25",
  "io.circe" %% "circe-core" % "0.14.7",
  "io.circe" %% "circe-generic" % "0.14.7",
  "io.circe" %% "circe-parser" % "0.14.7"
)