package org.allenai.pdffigures2

import org.allenai.pdffigures2.FigureExtractor.{ Document, DocumentWithSavedFigures }
import org.allenai.pdffigures2.SectionedTextBuilder.{ DocumentSection, PdfText }

import spray.json._

// From https://github.com/spray/spray-json/issues/200
// to support enum -> json conversion
class EnumJsonConverter[T <: scala.Enumeration](enu: T) extends RootJsonFormat[T#Value] {
  override def write(obj: T#Value): JsValue = JsString(obj.toString)

  override def read(json: JsValue): T#Value = {
    json match {
      case JsString(txt) => enu.withName(txt)
      case somethingElse => throw DeserializationException(s"Expected a value from enum $enu instead of $somethingElse")
    }
  }
}

trait JsonProtocol extends DefaultJsonProtocol {
  // JSON formats so we can write Figures/Captions/Documents to disk
  implicit val enumConverter = new EnumJsonConverter(FigureType)
  implicit val boxFormat = jsonFormat4(Box.apply)
  implicit val captionFormat = jsonFormat5(Caption.apply)
  implicit val figureFormat = jsonFormat7(Figure.apply)
  implicit val savedFigureFormat = jsonFormat9(SavedFigure.apply)
  implicit val documentTextFormat = jsonFormat3(PdfText.apply)
  implicit val documentSectionFormat = jsonFormat2(DocumentSection.apply)
  implicit val documentFormat = jsonFormat3(Document.apply)
  implicit val documentWithFiguresFormat = jsonFormat3(DocumentWithSavedFigures.apply)
}

object JsonProtocol extends JsonProtocol
