package era7bio

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

  type +[A,B] =
    Either[A,B]
}

package basespace {

  case class BasespaceFile(
    val id   : ID,
    val name : String,
    val url  : URL
  )

  case class Biosample(
    val id        : ID,
    val name      : String,
    val url       : URL,
    val projectID : ID
  )

  case class Dataset(
    val id          : ID,
    val name        : String,
    val date        : Date,
    val projectID   : ID,
    val datasetType : String
  )
}
