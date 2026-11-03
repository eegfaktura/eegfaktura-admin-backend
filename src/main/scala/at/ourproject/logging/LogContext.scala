package at.ourproject.logging

import org.apache.pekko.event.LoggingAdapter
import org.slf4j.{Logger, LoggerFactory, MDC}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

/**
 * `request.id` and `tenant.id` on all lines of an HTTP request, not only the access line: they sit in the MDC, which
 * the `EcsEncoder` reads, but the MDC stays on its thread while a request moves between threads. So they are carried
 * over at the points it does that: the request's `ExecutionContext` (`AccessLog.accessLog`), an actor message
 * (`Carrier` and `Behaviors.withMdc`) and a `Future` started while handling one (`inherited`).
 */
object LogContext {

  /** The field names, the same on every line that carries them. */
  val RequestId = "request.id"
  val TenantId = "tenant.id"

  private val Keys = Seq(RequestId, TenantId)

  /** The fields of the current thread. */
  def current: Map[String, String] = Keys.flatMap(k => Option(MDC.get(k)).map(k -> _)).toMap

  private def set(fields: Map[String, String]): Unit = {
    Keys.foreach(MDC.remove)
    fields.foreach { case (k, v) => MDC.put(k, v) }
  }

  /** Adds to the fields of the current thread, up to the end of the surrounding `scoped`. */
  def put(fields: Map[String, String]): Unit = fields.foreach { case (k, v) => MDC.put(k, v) }

  def scoped[T](fields: Map[String, String])(body: => T): T = {
    val previous = current
    set(fields)
    try body finally set(previous)
  }

  /** `ec`, running each task with `fields()`, read when the task starts. */
  def on(ec: ExecutionContext)(fields: () => Map[String, String]): ExecutionContextExecutor = new ExecutionContextExecutor {
    override def execute(task: Runnable): Unit = ec.execute(() => scoped(fields())(task.run()))
    override def reportFailure(cause: Throwable): Unit = ec.reportFailure(cause)
  }

  /** `ec` with the fields of the current thread, for callbacks that run after the current message is done. */
  def inherited(ec: ExecutionContext): ExecutionContext = {
    val fields = current
    on(ec)(() => fields)
  }

  /** For actor messages: keeps the fields of the thread that creates the message, for `Behaviors.withMdc`. */
  trait Carrier {
    val logFields: Map[String, String] = current
  }

  /**
   * `RequestContext.log`, where Pekko's default exception handler writes its error line. The usual adapter goes
   * through the event bus, and the line would lose the fields.
   */
  private[logging] object RouteLog extends LoggingAdapter {
    private val log: Logger = LoggerFactory.getLogger("at.ourproject.http")

    override def isErrorEnabled: Boolean = log.isErrorEnabled
    override def isWarningEnabled: Boolean = log.isWarnEnabled
    override def isInfoEnabled: Boolean = log.isInfoEnabled
    override def isDebugEnabled: Boolean = log.isDebugEnabled

    override protected def notifyError(message: String): Unit = log.error(message)
    override protected def notifyError(cause: Throwable, message: String): Unit = log.error(message, cause)
    override protected def notifyWarning(message: String): Unit = log.warn(message)
    override protected def notifyInfo(message: String): Unit = log.info(message)
    override protected def notifyDebug(message: String): Unit = log.debug(message)
  }
}
