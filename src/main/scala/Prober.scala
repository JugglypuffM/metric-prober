import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.Port
import fs2.Stream
import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.{CollectorRegistry, Counter, Histogram}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.{Authorization, `Content-Type`}
import org.typelevel.ci.CIString

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.*

object Prober extends IOApp.Simple {

  final case class Config(
                           oncallBaseUrl: Uri,
                           period: FiniteDuration,
                           clientTimeout: FiniteDuration,
                           proberPort: Int,
                           checkerPort: Int,
                           sageBaseUrl: Uri,
                           sageToken: String
                         )

  final case class PostMetricBody(
                                   query: String,
                                   size: Int,
                                   startTime: String,
                                   endTime: String
                                 )

  implicit val enc: Encoder[PostMetricBody] = deriveEncoder

  final case class Hit(value: Double)

  final case class HitsResponse(hits: List[Hit])

  implicit val decHit: Decoder[Hit] = Decoder.forProduct1("value")(Hit.apply)
  implicit val dec: Decoder[HitsResponse] =
    Decoder.forProduct1("hits")(HitsResponse.apply)


  private val registry: CollectorRegistry = new CollectorRegistry()

  private val requestCounter: Counter =
    Counter
      .build()
      .name("prober_requests_total")
      .help("Total number of HTTP requests attempted by prober")
      .labelNames("endpoint", "result")
      .register(registry)

  private val latencyHistogram: Histogram =
    Histogram
      .build()
      .name("prober_request_duration_seconds")
      .help("HTTP request duration in seconds by endpoint")
      .labelNames("endpoint")
      .buckets(0.01, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0)
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
        IO.pure(latencyHistogram.labels(uri.renderString).observe(seconds))
      case Left(_) => IO.unit
    }
  }
  
  private def probeCreateTeam(client: Client[IO]): IO[Unit] = {
    
  }

  def lastValue(client: Client[IO], query: String): IO[Double] = {

    val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
    val start = now.minus(1, ChronoUnit.MINUTES).toString
    val end = now.plus(1, ChronoUnit.MINUTES).toString
    val body = PostMetricBody(query, size = 1, startTime = start, endTime = end)

    val uri = Uri.unsafeFromString(s"${cfg.sageBaseUrl.renderString}/mage/api/search")

    val authHeader: Authorization =
      Authorization(Credentials.Token(AuthScheme.Bearer, cfg.sageToken))


    val sourceHeader: Header.ToRaw =
      Header.Raw(CIString("SOURCE"), "metric-prober")

    val req = Request[IO](method = POST, uri = uri)
      .withEntity(body)
      .putHeaders(authHeader, sourceHeader)

    client.expect[HitsResponse](req).flatMap { r =>
      r.hits.headOption match {
        case Some(hit) => IO.pure(hit.value)
        case None => IO.raiseError(new RuntimeException("Empty hits in response"))
      }
    }
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
    clientTimeout = 4.seconds,
    proberPort = 8080,
    checkerPort = 8081,
    sageBaseUrl = Uri.unsafeFromString("https://sage.sre-ab.ru"),
    sageToken = ""
  )

  private def clientResource(timeout: FiniteDuration): Resource[IO, org.http4s.client.Client[IO]] =
    EmberClientBuilder
      .default[IO]
      .withTimeout(timeout)
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
        client <- clientResource(cfg.clientTimeout)
        _ <- serverResource(app, cfg.proberPort)
      } yield client

    res.use { client =>
      probeStream(client, cfg.period).compile.drain
    }
  }
}
