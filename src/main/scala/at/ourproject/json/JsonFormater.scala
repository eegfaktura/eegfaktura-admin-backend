package at.ourproject.json

import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}

object JsonFormater {
  implicit val SqlDateFormat : Encoder[java.sql.Date] with Decoder[java.sql.Date] = new Encoder[java.sql.Date] with Decoder[java.sql.Date] {
    override def apply(a: java.sql.Date): Json = Encoder.encodeLong.apply(a.getTime)

    override def apply(c: HCursor): Result[java.sql.Date] = Decoder.decodeLong.map(s => new java.sql.Date(s)).apply(c)
  }
}
