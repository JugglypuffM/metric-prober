import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.Port
import fs2.Stream
import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`

import scala.concurrent.duration.*

object Main extends IOApp.Simple {

  final case class Config(
                           oncallBaseUrl: Uri,
                           period: FiniteDuration,
                           proberPort: Int,
                         )

  final case class CreateTeamRequest(
                                      name: String,
                                      scheduling_timezone: String = "US/Pacific"
                                    )

  implicit val createTeamRequestEncoder: Encoder[CreateTeamRequest] = deriveEncoder

  final case class Hit(value: Double)

  final case class HitsResponse(hits: List[Hit])

  implicit val decHit: Decoder[Hit] = Decoder.forProduct1("value")(Hit.apply)
  implicit val dec: Decoder[HitsResponse] =
    Decoder.forProduct1("hits")(HitsResponse.apply)


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

  private def metricsRoutes(reg: CollectorRegistry): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "metrics" =>
      IO.blocking {
        val writer = new java.io.StringWriter()
        TextFormat.write004(writer, reg.metricFamilySamples())
        writer.toString
      }.flatMap { body =>
        Ok(body).map(_.putHeaders(`Content-Type`(MediaType.text.plain)))
      }
  }

  private def probeGetTeams(client: Client[IO]): IO[Unit] = {
    val uri = cfg.oncallBaseUrl / "teams" / "all"
    
    client.status(Request[IO](Method.GET, uri)).timed.attempt.flatMap {
      case Right((dur, status)) =>
        val seconds = dur.toNanos.toDouble / 1e9
        IO.pure(latencyGauge.set(seconds))
      case Left(_) => IO.unit
    }
  }

  private def probeCreateTeam(client: Client[IO]): IO[Unit] = {
    val createRequest = CreateTeamRequest("MetricTestTeam")

    val uri = cfg.oncallBaseUrl / "teams"

    client.status(Request[IO](Method.POST, uri).withEntity(createRequest)).timed.attempt.flatMap {
      case Right((dur, status)) =>
        val result = if (status.isSuccess) "success" else "failure"
        IO.pure(requestCounter.labels(result).inc())
      case Left(_) => IO.pure(requestCounter.labels("failure").inc())
    } *> client.status(Request[IO](Method.DELETE, uri / createRequest.name)).attempt.as(())
  }

  private def probeStream(
                           client: org.http4s.client.Client[IO],
                           period: FiniteDuration
                         ): Stream[IO, Unit] = {
    Stream.awakeEvery[IO](period).evalMap(_ => probeGetTeams(client) *> probeCreateTeam(client))
  }

  private val cfg: Config = Config(
    oncallBaseUrl = Uri.unsafeFromString("http://oncall.st-ab2-klinin.ingress.sre-ab.ru"),
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
    val app = metricsRoutes(registry).orNotFound

    val res =
      for {
        client <- clientResource
        _ <- serverResource(app, cfg.proberPort)
      } yield client

    res.use { client =>
      probeStream(client, cfg.period).compile.drain
    }
  }
}
