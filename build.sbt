ThisBuild / version := "1"

ThisBuild / scalaVersion := "3.7.4"

lazy val root = (project in file("."))
  .settings(
    name := "metric-prober"
  )

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "3.6.3",
  "co.fs2" %% "fs2-core" % "3.12.2",
  "org.http4s" %% "http4s-ember-server" % "0.23.33",
  "org.http4s" %% "http4s-ember-client" % "0.23.33",
  "org.http4s" %% "http4s-dsl" % "0.23.33",
  "io.prometheus" % "simpleclient" % "0.16.0",
  "io.prometheus" % "simpleclient_common" % "0.16.0",
  "org.http4s" %% "http4s-circe" % "0.23.33",
  "io.circe" %% "circe-core" % "0.14.15",
  "io.circe" %% "circe-generic" % "0.14.15",
  "io.circe" %% "circe-parser" % "0.14.15",
  "ch.qos.logback" % "logback-classic" % "1.5.21",
  "tf.tofu" %% "tofu-logging" % "0.14.0",
  "tf.tofu" %% "tofu-logging-derivation" % "0.14.0",
  "tf.tofu" %% "tofu-core-ce3" % "0.14.0",
)

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerBaseImage := "openjdk:25-ea"
dockerExposedPorts := Seq(8889)