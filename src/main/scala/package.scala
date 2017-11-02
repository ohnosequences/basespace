package era7bio

package object basespace {

  type ID =
    String

  type URL =
    String

  type BaseURL =
    URL

  val baseURLv1: BaseURL = "https://api.basespace.illumina.com/v1pre3"
  val baseURLv2: BaseURL = "https://api.basespace.illumina.com/v2"

  type +[A,B] =
    Either[A,B]
}

package basespace {

  case class BasespaceFile(
    val name : String,
    val url  : URL,
    val id   : ID
  )
}
