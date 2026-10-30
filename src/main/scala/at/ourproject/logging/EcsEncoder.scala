package at.ourproject.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxyUtil}
import ch.qos.logback.core.encoder.EncoderBase
import com.fasterxml.jackson.core.JsonFactory

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._

/**
 * Writes a log event as one line with ECS field names: format `json` one JSON object per line, `text` the same fields
 * as a readable line. Only the fields in `Fields` are taken from the event's key-value pairs or MDC; anything else
 * (Pekko's MDC entries, a field someone adds later) is dropped - the never-log guard of the logging concept.
 */
class EcsEncoder extends EncoderBase[ILoggingEvent] {
  import EcsEncoder._

  private var format = "json"
  private var serviceVersion = "dev"
  private val json = new JsonFactory()

  /** `text` or `json`; unset, empty and unknown are json (an unknown value is refused at the start before). */
  def setFormat(f: String): Unit = format = Option(f).flatMap(LogSettings.parseFormat).getOrElse("json")
  def setServiceVersion(v: String): Unit = serviceVersion = Option(v).map(_.trim).filter(_.nonEmpty).getOrElse("dev")

  override def headerBytes(): Array[Byte] = null
  override def footerBytes(): Array[Byte] = null

  override def encode(event: ILoggingEvent): Array[Byte] = {
    val fields = fieldsOf(event)
    val line = if (format == "text") text(fields) else jsonLine(fields)
    (line + "\n").getBytes(StandardCharsets.UTF_8)
  }

  /** The fields of the line, in output order. */
  def fieldsOf(event: ILoggingEvent): Seq[(String, Any)] = {
    val present: Map[String, Any] =
      Option(event.getMDCPropertyMap).map(_.asScala.toMap).getOrElse(Map.empty[String, String]) ++
        Option(event.getKeyValuePairs).map(_.asScala.map(kv => kv.key -> kv.value).toMap).getOrElse(Map.empty)
    val error = Option(event.getThrowableProxy).toSeq.flatMap { t =>
      Seq("error.type" -> t.getClassName, "error.message" -> t.getMessage, "error.stack_trace" -> ThrowableProxyUtil.asString(t))
    }
    Seq(
      "@timestamp" -> Timestamp.format(event.getInstant),
      "log.level" -> levelName(event.getLevel),
      "log.logger" -> event.getLoggerName,
      "service.name" -> LogSettings.ServiceName,
      "service.version" -> serviceVersion,
    ) ++ Fields.flatMap(f => present.get(f).filter(_ != null).map(f -> _)) ++
      Seq("message" -> event.getFormattedMessage) ++ error.filter(_._2 != null)
  }

  private def jsonLine(fields: Seq[(String, Any)]): String = {
    val out = new ByteArrayOutputStream(256)
    val g = json.createGenerator(out)
    g.writeStartObject()
    fields.foreach {
      case (k, v: java.lang.Integer) => g.writeNumberField(k, v.intValue)
      case (k, v: java.lang.Long) => g.writeNumberField(k, v.longValue)
      case (k, v) => g.writeStringField(k, String.valueOf(v))
    }
    g.writeEndObject()
    g.close()
    out.toString(StandardCharsets.UTF_8)
  }

  private def text(fields: Seq[(String, Any)]): String = {
    val f = fields.toMap
    val extra = fields.filterNot { case (k, _) => TextHead(k) }.map { case (k, v) => s"$k=${quoted(String.valueOf(v))}" }
    val head = f"${f("@timestamp")} ${f("log.level")}%-5s ${f("log.logger")}: ${f("message")}"
    (head +: extra).mkString(" ") + f.get("error.stack_trace").map("\n" + _).getOrElse("")
  }
}

object EcsEncoder {
  /** The fields a log call (key-value pairs) or the MDC may add; everything else is dropped. */
  val Fields: Seq[String] = Seq(
    "event.action", "event.outcome", "event.duration",
    "http.request.method", "http.route", "http.response.status_code",
    "request.id", "trace.id", "span.id", "tenant.id", "client.id")

  private val Timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
  private val TextHead = Set("@timestamp", "log.level", "log.logger", "message", "service.name", "service.version", "error.stack_trace")

  def levelName(level: Level): String = level.toInt match {
    case l if l <= Level.TRACE_INT => "trace"
    case l if l <= Level.DEBUG_INT => "debug"
    case l if l <= Level.INFO_INT => "info"
    case l if l <= Level.WARN_INT => "warn"
    case _ => "error"
  }

  private def quoted(v: String): String =
    if (v.nonEmpty && !v.exists(c => c == ' ' || c == '"' || c == '=' || c == '\n')) v
    else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
