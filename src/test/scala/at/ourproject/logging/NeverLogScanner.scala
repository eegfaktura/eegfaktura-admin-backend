package at.ourproject.logging

/**
 * Finds what must never reach a log line (logging concept point 4), from the source text alone, so without a new
 * dependency. Comments and the text of string literals are blanked first; what is left of a log call is code and
 * interpolated expressions. A bare value (`$x`, `, x`) is a hit whatever its type (default-deny), because the source
 * does not tell a `String` from a case class; a plain one needs an entry in the allowlist of `NeverLogSpec`. A dotted
 * value is not default-deny: it is a hit by the names below or when it holds a model type, so `${eeg.contact.web}` passes.
 */
object NeverLogScanner {

  final case class Hit(file: String, line: Int, term: String, rule: String) {
    override def toString: String = s"$file:$line `$term`: $rule"
  }

  val BareValue = "bare value, may be a model type"
  val ToStringCall = "toString, may be a model type"
  val ModelField = "field of a model type"
  val MessageOnly = "getMessage without the exception"
  val PersonalName = "personal data or secret"

  private val LogCall = """(?:\b(?:log|logger)\.(?:trace|debug|info|warn|error)|getLogger\([^)]*\)\.(?:trace|debug|info|warn|error)|\.log|\.addKeyValue|\bprintln)\s*\(""".r
  // a chain of names; after a call too: `Option(x).email` is `email` here
  private val Term = """(?:(?<![\w.])|(?<=\)\.))[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*""".r
  private val CaseClass = """case class (\w+)""".r
  private val KeyLiteral = """^\s*"([^"]*)"""".r

  private val Keywords = Set("_", "true", "false", "null", "new", "if", "else", "this")
  private val ThrowableNames = Set("e", "ex", "cause", "exception", "t")
  private val MessageCalls = Set("getMessage", "getLocalizedMessage")
  // lower case; matched inside an identifier, so `getEmail`, `firstName` and `accessToken` count
  private val Names = Seq("firstname", "lastname", "email", "iban", "password", "secret", "token", "userid")
  // matched as a whole camel-case word, on every segment of a dotted value: `getSubject` and `eeg.contact.streetNumber`
  // count, `substring` does not
  private val Words = Set("sub", "subject", "username", "phone", "street", "owner", "name", "city", "zip")

  /**
   * `src` with comments and literal text blanked and interpolated expressions left as code; offsets and line breaks
   * stay as they are.
   */
  def mask(src: String): String = {
    val out = new StringBuilder(src)
    def blank(from: Int, to: Int): Unit = (from until to.min(src.length)).foreach(i => if (src(i) != '\n') out(i) = ' ')

    // code up to the end, or up to the `}` that closes `${`; the index it stopped at
    def code(start: Int, inBraces: Boolean): Int = {
      var i = start
      var depth = 0
      while (i < src.length) {
        val c = src(i)
        if (src.startsWith("//", i)) {
          val end = Some(src.indexOf('\n', i)).filter(_ >= 0).getOrElse(src.length)
          blank(i, end)
          i = end
        } else if (src.startsWith("/*", i)) {
          val end = Some(src.indexOf("*/", i)).filter(_ >= 0).map(_ + 2).getOrElse(src.length)
          blank(i, end)
          i = end
        } else if (c == '"') i = literal(i)
        else if (c == '\'' && (src.startsWith("\\", i + 1) || src.startsWith("'", i + 2))) {
          val end = src.indexOf('\'', i + 2) + 1
          blank(i, end)
          i = end
        } else if (c == '}' && inBraces && depth == 0) return i
        else {
          if (c == '{') depth += 1 else if (c == '}') depth -= 1
          i += 1
        }
      }
      i
    }

    def literal(start: Int): Int = {
      var prefix = start
      while (prefix > 0 && src(prefix - 1).isLetter) prefix -= 1
      val interpolated = prefix < start
      val quote = if (src.startsWith("\"\"\"", start)) "\"\"\"" else "\""
      blank(prefix, start + quote.length)
      var i = start + quote.length
      while (i < src.length && !src.startsWith(quote, i)) {
        if (quote.length == 1 && src(i) == '\\') { blank(i, i + 2); i += 2 }
        else if (interpolated && src.startsWith("$$", i)) { blank(i, i + 2); i += 2 }
        else if (interpolated && src.startsWith("${", i)) {
          blank(i, i + 2)
          i = code(i + 2, inBraces = true)
          blank(i, i + 1)
          i += 1
        } else if (interpolated && src(i) == '$') {
          blank(i, i + 1)
          i += 1
          while (i < src.length && src(i).isLetterOrDigit) i += 1
        } else { blank(i, i + 1); i += 1 }
      }
      blank(i, i + quote.length)
      i + quote.length
    }

    code(0, inBraces = false)
    out.toString
  }

  /** Names declared with a case class of these sources as type: `$eeg.user` is not bare, but still a whole object. */
  def modelFields(masked: Seq[String]): Set[String] = {
    val types = masked.flatMap(CaseClass.findAllMatchIn(_).map(_.group(1))).toSet ++ Set("UserRepresentation", "CredentialRepresentation")
    val declaration = ("""(\w+)\s*:\s*(?:Option\[|List\[|Seq\[)?(?:""" + types.mkString("|") + """)\b""").r
    masked.flatMap(declaration.findAllMatchIn(_).map(_.group(1))).toSet
  }

  /** The hits in one file; `calls` counts the log calls seen. */
  def scan(file: String, src: String, masked: String, models: Set[String], calls: () => Unit = () => ()): List[Hit] =
    LogCall.findAllMatchIn(masked).toList.flatMap { call =>
      calls()
      val end = closing(masked, call.end)
      val args = masked.substring(call.end, end)
      val line = masked.substring(0, call.start).count(_ == '\n') + 1
      val parts = topLevelArgs(args)
      val throwable = parts.lastOption.map(_.trim).filter(a => parts.size > 1 && ThrowableNames(a))
      val code = if (throwable.isDefined) parts.init.mkString(",") else args
      val key = if (call.matched.contains("addKeyValue")) KeyLiteral.findFirstMatchIn(src.substring(call.end, end)).map(_.group(1)) else None
      val keyHit = key.filter(isPersonal).map(k => Hit(file, line, k, PersonalName)).toList
      keyHit ++ Term.findAllMatchIn(code).toList.flatMap { t =>
        val isCall = code.substring(t.end).trim.startsWith("(")
        rule(t.matched, isCall, throwable.isDefined, models).filterNot(_ => Keywords(t.matched)).map(Hit(file, line, t.matched, _))
      }
    }

  private def rule(term: String, isCall: Boolean, hasThrowable: Boolean, models: Set[String]): Option[String] = {
    val segments = term.split('.').toList
    if (segments.exists(isPersonal)) Some(PersonalName)
    else if (segments.contains("toString")) Some(ToStringCall)
    else if (segments.exists(MessageCalls)) if (hasThrowable) None else Some(MessageOnly)
    else if (isCall) None
    else if (segments.size == 1) Some(BareValue)
    else if (models(segments.last)) Some(ModelField)
    else None
  }

  private def isPersonal(identifier: String): Boolean = {
    val lower = identifier.toLowerCase
    Names.exists(lower.contains) || identifier.split("(?=[A-Z])|[._]").map(_.toLowerCase).exists(Words)
  }

  private def closing(masked: String, open: Int): Int = {
    var depth = 1
    var i = open
    while (depth > 0 && i < masked.length) {
      if (masked(i) == '(') depth += 1 else if (masked(i) == ')') depth -= 1
      i += 1
    }
    i - 1
  }

  private def topLevelArgs(args: String): List[String] = {
    var depth = 0
    var start = 0
    val parts = List.newBuilder[String]
    args.zipWithIndex.foreach {
      case ('(' | '[' | '{', _) => depth += 1
      case (')' | ']' | '}', _) => depth -= 1
      case (',', i) if depth == 0 => parts += args.substring(start, i); start = i + 1
      case _ =>
    }
    (parts += args.substring(start)).result()
  }
}
