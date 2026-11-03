package at.ourproject.testsupport

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/** The events a logger (and the loggers below it) writes while a test runs. */
final class LogCapture private () {
  private val queue = new ConcurrentLinkedQueue[ILoggingEvent]

  private[testsupport] val appender = new AppenderBase[ILoggingEvent] {
    // the MDC of an event is read on the thread that logs it, not when the test looks
    override def append(event: ILoggingEvent): Unit = { event.prepareForDeferredProcessing(); queue.add(event) }
  }

  def events: List[ILoggingEvent] = queue.asScala.toList
}

object LogCapture {

  /**
   * Runs `body` with an appender on the logback logger `name` (ROOT for all), at `level` when given; the appender and
   * the level are taken back afterwards.
   */
  def apply[T](name: String, level: Option[Level] = None)(body: LogCapture => T): T = {
    val logger = LoggerFactory.getLogger(name).asInstanceOf[LogbackLogger]
    val capture = new LogCapture
    val previous = logger.getLevel
    capture.appender.setContext(logger.getLoggerContext)
    capture.appender.start()
    logger.addAppender(capture.appender)
    level.foreach(logger.setLevel)
    try body(capture)
    finally {
      if (level.isDefined) logger.setLevel(previous)
      logger.detachAppender(capture.appender)
      capture.appender.stop()
    }
  }
}
