import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.Port
import fs2.Stream
import io.circe.Encoder
import io.circe.generic.semiauto.*
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.{CollectorRegistry, Counter, Gauge}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import tofu.logging.Logging
import tofu.syntax.logging.*

import scala.concurrent.duration.*

object Main extends IOApp.Simple {

  private final case class Config(
                                   oncallBaseUrl: Uri,
                                   period: FiniteDuration,
                                   proberPort: Int,
                                 )

  final case class CreateTeamRequest(
                                      name: String,
                                      scheduling_timezone: String = "US/Pacific"
                                    )

  implicit val createTeamRequestEncoder: Encoder[CreateTeamRequest] = deriveEncoder

  private val registry: CollectorRegistry = new CollectorRegistry()

  private val requestCounter: Counter =
    Counter
      .build()
      .name("create_team_requests_total")
      .help("Total number of HTTP requests attempted by prober")
      .labelNames("result")
      .register(registry)

  private val latencyGauge: Gauge =
    Gauge
      .build()
      .name("watch_teams_request_duration_seconds")
      .help("HTTP request duration in seconds by endpoint")
      .register(registry)

  private def metricsRoutes(reg: CollectorRegistry)(using lm: Logging.Make[IO]): HttpRoutes[IO] =
    def getMetrics = IO.blocking {
      val writer = new java.io.StringWriter()
      TextFormat.write004(writer, reg.metricFamilySamples())
      writer.toString
    }

    given Logging[IO] = lm.byName("MetricsLogger")

    HttpRoutes.of[IO] {
      case GET -> Root / "metrics" =>
        for {
          _ <- info"Got request on /metrics"
          body <- getMetrics
          _ <- info"Found metrics: $body"
          response <- Ok(body).map(_.putHeaders(`Content-Type`(MediaType.text.plain)))
          _ <- info"Sending response"
        } yield response
    }

  private def probeGetTeams(client: Client[IO])(using lm: Logging.Make[IO]): IO[Unit] = {
    given Logging[IO] = lm.byName("GetTeamsProber")

    val uri = cfg.oncallBaseUrl / "teams" / "all"

    client.status(Request[IO](Method.GET, uri)).timed.attempt.flatMap {
      case Right((dur, status)) =>
        val seconds = dur.toNanos.toDouble / 1e9
        IO.pure(latencyGauge.set(seconds))
      case Left(_) => IO.unit
    }
    for {
      _ <- info"Sending GET teams request"
      eitherGetResult <- client.status(Request[IO](Method.GET, uri)).timed.attempt
      _ <- eitherGetResult match {
        case Right((dur, status)) =>
          val seconds = dur.toNanos.toDouble / 1e9
          if (status.isSuccess) info"GET teams request completed with success in $seconds seconds" *> IO.pure(latencyGauge.set(seconds))
          else info"GET teams request completed with failure in $seconds seconds" *> IO.unit
        case Left(_) => info"GET teams request failed" *> IO.unit
      }
    } yield ()
  }

  private def probeCreateTeam(client: Client[IO])(using lm: Logging.Make[IO]): IO[Unit] = {
    given Logging[IO] = lm.byName("CreateTeamProber")

    val createRequest = CreateTeamRequest("MetricTestTeam")

    val uri = cfg.oncallBaseUrl / "teams"

    for {
      _ <- info"Sending CREATE team request"
      eitherCreateResult <- client.status(Request[IO](Method.POST, uri).withEntity(createRequest)).attempt
      _ <- eitherCreateResult match {
        case Right(status) =>
          val result = if (status.isSuccess) "success" else "failure"
          info"CREATE team request completed with $result" *> IO.pure(requestCounter.labels(result).inc())
        case Left(_) => info"CREATE team request failed"
      }
      _ <- info"Sending DELETE team request"
      eitherDeleteResult <- client.status(Request[IO](Method.DELETE, uri / createRequest.name)).attempt
      _ <- eitherDeleteResult match {
        case Right(status) =>
          val result = if (status.isSuccess) "success" else "failure"
          info"DELETE team request completed with $result"
        case Left(_) => info"DELETE team request failed"
      }
    } yield ()
  }

  private def probeStream(
                           client: org.http4s.client.Client[IO],
                           period: FiniteDuration
                         )(using lm: Logging.Make[IO]): Stream[IO, Unit] = {
    given Logging[IO] = lm.byName("ProberJob")

    Stream.awakeEvery[IO](period).evalMap(_ =>
      for {
        _ <- info"Probing GET teams"
        _ <- probeGetTeams(client)
        _ <- info"Probing CREATE team"
        _ <- probeCreateTeam(client)
      } yield ()
    )
  }

  private val cfg: Config = Config(
    oncallBaseUrl = Uri.unsafeFromString("http://localhost:8080"),
    period = 5.seconds,
    proberPort = 8889
  )

  private def clientResource: Resource[IO, org.http4s.client.Client[IO]] =
    EmberClientBuilder
      .default[IO]
      .build

  private def serverResource(app: HttpApp[IO], port: Int): Resource[IO, org.http4s.server.Server] =
    EmberServerBuilder
      .default[IO]
      .withPort(Port.fromInt(port).get)
      .withHttpApp(app)
      .build

  def run: IO[Unit] = {
    given lm: Logging.Make[IO] = Logging.Make.plain[IO]

    given Logging[IO] = lm.byName("ProberLogger")

    val metricsServer = metricsRoutes(registry).orNotFound

    val app = for {
      _ <- Stream.eval(info"Building client")
      client <- Stream.resource(clientResource)
      _ <- Stream.eval(info"Starting metric server")
      server <- Stream.resource(serverResource(metricsServer, cfg.proberPort))
      _ <- Stream.eval(info"Starting prober job")
      _ <- probeStream(client, cfg.period)
    } yield ()

    app.compile.drain
  }
}
