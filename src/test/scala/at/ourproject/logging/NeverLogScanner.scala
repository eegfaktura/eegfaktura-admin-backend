package at.ourproject.logging

/**
 * Finds what must never reach a log line (logging concept point 4), from the source text alone, so without a new
 * dependency. Comments and the text of string literals are blanked first; what is left of a log call is code and
 * interpolated expressions. In the text of a literal only a list word right before a placeholder counts. A bare value
 * (`$x`, `, x`) is a hit (default-deny) unless the file declares it as number, boolean or exception, because the source
 * does not tell a `String` from a case class; a plain one needs an entry in the allowlist of `NeverLogSpec`. A dotted
 * value is not default-deny: it is a hit by the names below or when it holds a model type, so `${eeg.contact.web}` passes.
 */
object NeverLogScanner {

  final case class Hit(file: String, line: Int, term: String, rule: String) {
    override def toString: String = s"$file:$line `$term`: $rule"
  }

  /** The hits of a file and the number of log calls seen. */
  final case class Result(hits: List[Hit], calls: Int)

  val BareValue = "bare value, may be a model type"
  val ToStringCall = "toString, may be a model type"
  val ModelField = "field of a model type"
  val MessageOnly = "getMessage without the exception"
  val PersonalName = "personal data or secret"
  val BeforePlaceholder = "list word before a placeholder"

  private val LogCall = """(?:\b(?:log|logger)\.(?:trace|debug|info|warn|error)|getLogger\([^)]*\)\.(?:trace|debug|info|warn|error)|\.log|\.addKeyValue|\bprintln)\s*\(""".r
  // a chain of names; after a call too: `Option(x).email` is `email` here
  private val Term = """(?:(?<![\w.])|(?<=\)\.))[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*""".r
  private val CaseClass = """case class (\w+)""".r
  // a list word in the text of a literal, then a placeholder: `password=%s`, `"token: {}"`, `s"secret=$x"`, `${x}`
  private val Placeholder = """([A-Za-z][A-Za-z0-9_]*)[ \t]*[:=]?[ \t]*(?:\{\}|%[-#+ 0-9.]*[a-zA-Z]|\$\{|\$[A-Za-z_])""".r
  // values that are safe by their declared type: numbers, booleans, exceptions
  private val SafeType = """(\w+)\s*:\s*(?:Int|Long|Short|Byte|Double|Float|Boolean|Throwable|\w*Exception|\w*Error)\b""".r
  private val FailureBinding = """\bFailure\((\w+)\b""".r

  private val Keywords = Set("_", "true", "false", "null", "new", "if", "else", "this")
  private val MessageCalls = Set("getMessage", "getLocalizedMessage")
  // the list of all services. An identifier is split into words (camelCase, snake_case); an entry matches one word or
  // two in a row written together, also with a plural s: `getEmail`, `first_name`, `accessTokens`, `apiKey`,
  // `getSubject` count, `substring` does not. In an all-lower-case identifier an entry of five or more letters also
  // matches inside it (`userpassword`). Every segment of a dotted value is checked.
  private val Words = Set("password", "passwd", "pwd", "secret", "token", "jwt", "bearer", "authorization", "credential",
    "creds", "cookie", "apikey", "iban", "bic", "firstname", "lastname", "vorname", "nachname", "name1", "name2", "street",
    "phone", "email", "mailaddress", "recipient", "sentto", "owner", "accountholder", "username", "userid", "sub",
    "subject", "payload", "body")
  private val WordBreak = "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_".r

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

  /**
   * The hits in one file (`masked` is `mask` of `src`) and the number of log calls seen. A bare value declared in the
   * file as number, boolean or exception (`status: Int`, `case ex: SQLException`, `Failure(e)`) is safe by its type;
   * an exception as last argument is the exception of the line.
   */
  def scan(file: String, src: String, masked: String, models: Set[String]): Result = {
    val safe = SafeType.findAllMatchIn(masked).map(_.group(1)).toSet ++ FailureBinding.findAllMatchIn(masked).map(_.group(1))
    val calls = LogCall.findAllMatchIn(masked).toList
    val hits = calls.flatMap { call =>
      val end = closing(masked, call.end)
      val args = masked.substring(call.end, end)
      val line = masked.substring(0, call.start).count(_ == '\n') + 1
      val parts = topLevelArgs(args)
      val throwable = parts.lastOption.map(_.trim).filter(a => parts.size > 1 && safe(a))
      val code = if (throwable.isDefined) parts.init.mkString(",") else args
      val literalWords = Placeholder.findAllMatchIn(src.substring(call.end, end)).toList
        .filter { m => val i = call.end + m.start; masked(i) == ' ' && src(i) != ' ' } // in the text of a literal
        .filter(m => isPersonal(m.group(1))).map(m => Hit(file, line, m.group(1), BeforePlaceholder))
      literalWords ++ Term.findAllMatchIn(code).toList.flatMap { t =>
        val isCall = code.substring(t.end).trim.startsWith("(")
        rule(t.matched, isCall, throwable.isDefined, models, safe).filterNot(_ => Keywords(t.matched)).map(Hit(file, line, t.matched, _))
      }
    }
    Result(hits, calls.size)
  }

  private def rule(term: String, isCall: Boolean, hasThrowable: Boolean, models: Set[String], safe: Set[String]): Option[String] = {
    val segments = term.split('.').toList
    if (segments.exists(isPersonal)) Some(PersonalName)
    else if (segments.contains("toString")) Some(ToStringCall)
    else if (segments.exists(MessageCalls)) if (hasThrowable) None else Some(MessageOnly)
    else if (isCall) None
    else if (segments.size == 1) if (safe(term)) None else Some(BareValue)
    else if (models(segments.last)) Some(ModelField)
    else None
  }

  private def isPersonal(identifier: String): Boolean = {
    val words = WordBreak.split(identifier).filter(_.nonEmpty).map(_.toLowerCase).toList
    def listed(word: String) = Words(word) || (word.endsWith("s") && Words(word.dropRight(1)))
    words.exists(listed) || words.sliding(2).exists(pair => pair.size == 2 && listed(pair.mkString)) ||
      (!identifier.exists(_.isUpper) && Words.exists(w => w.length >= 5 && identifier.contains(w)))
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
