package era7bio

import play.api.libs.json._
import play.api.libs.functional.syntax._

package object basespace {

  type ID =
    String

  type URL =
    String

  type BaseURL =
    URL

  type Date =
    java.util.Date

  val baseURLv1: BaseURL = "https://api.basespace.illumina.com/v1pre3"
  val baseURLv2: BaseURL = "https://api.basespace.illumina.com/v2"

  type +[A, B] =
    Either[A, B]

  object DataFormatImplicits {
    implicit val basespaceFileFormat: Format[BasespaceFile] = (
      (JsPath \ "Id").format[ID] and
        (JsPath \ "Name").format[String] and
        (JsPath \ "HrefContent").format[URL] and
        (JsPath \ "DateCreated").format[Date] and
        (JsPath \ "Size").format[Long] and
        (JsPath \ "DatasetName").formatNullable[String]
    )(BasespaceFile.apply, unlift(BasespaceFile.unapply))

    implicit val biosampleFormat: Format[Biosample] = (
      (JsPath \ "Id").format[ID] and
        (JsPath \ "Href").format[URL] and
        (JsPath \ "BioSampleName").format[String] and
        (JsPath \ "DefaultProject" \ "Id").format[ID]
    )(Biosample.apply, unlift(Biosample.unapply))

    implicit val datasetFormat: Format[Dataset] = (
      (JsPath \ "Id").format[ID] and
        (JsPath \ "Name").format[String] and
        (JsPath \ "DateCreated").format[Date] and
        (JsPath \ "Project" \ "Name").format[String] and
        (JsPath \ "DatasetType" \ "Id").format[String] and
        (JsPath \ "TotalSize").format[Long]
    )(Dataset.apply, unlift(Dataset.unapply))

    implicit val projectFormat: Format[Project] = (
      (JsPath \ "Id").format[ID] and
        (JsPath \ "Name").format[String] and
        (JsPath \ "Description").format[String] and
        (JsPath \ "DateCreated").format[Date] and
        (JsPath \ "ImportableDatasets").formatNullable[Long]
    )(Project.apply, unlift(Project.unapply))
  }
}

package basespace {

  final case class BasespaceFile(
      val id: ID,
      val name: String,
      val url: URL,
      val date: Date,
      val size: Long,
      val datasetName: Option[String]
  )

  final case class PairedFASTQ(
      val R1: BasespaceFile,
      val R2: BasespaceFile
  )

  final case class Biosample(
      val id: ID,
      val name: String,
      val url: URL,
      val projectID: ID
  )

  final case class Dataset(
      val id: ID,
      val name: String,
      val date: Date,
      val projectName: String,
      val datasetType: String,
      val size: Long
  )

  final case class Project(
      val id: ID,
      val name: String,
      val description: String,
      val date: Date,
      val importableDatasets: Option[Long]
  )
}
