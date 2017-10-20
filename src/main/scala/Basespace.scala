package era7bio.basespace

import play.api.libs.ws.{StandaloneWSClient, StandaloneWSRequest, WSAuthScheme}
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.json._
import akka.util.ByteString
import akka.stream.scaladsl.Sink
import akka.stream.Materializer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits._
import scala.util.{ Success, Failure }
import javax.inject.{ Singleton, Inject }
import java.io.File
import java.nio.file.Files.newOutputStream

/**
 * Encapsulates BaseURL type and its values
 */
object BaseSpace{
  type BaseURL = String
  val baseURLv1: BaseSpace.BaseURL = "https://api.basespace.illumina.com/v1pre3"
  val baseURLv2: BaseSpace.BaseURL = "https://api.basespace.illumina.com/v2"
}

@Singleton
class BaseSpaceAuth @Inject() (
  val clientID     : String,
  val clientSecret : String,
  ws               : StandaloneWSClient
) {
  /**
   * Builds the URL to start the BaseSpace authorization process
   *
   * @param redirect The user will be redirected to this URL when the
   *                 authentication process finishes.
   * @param scope    The permissions that will be granted if the authentication
   *                 process finishes successfully.
   */
   def accessURL(redirect: String, scope: String) =
     "https://basespace.illumina.com/oauth/authorize" +
     "?client_id="     + clientID                     +
     "&redirect_uri="  + redirect                     +
     "&response_type=" + "code"                       +
     "&scope="         + scope


  /**
   * Performs POST request to exchange authentication code for access token.
   *
   * @param redirect The user will be redirected to this URL when the
   *                 authentication process finishes.
   * @param code     The authentication code received after visiting
   *                 [[accessURL]].
   */
  def authenticate(redirect: String, code: String): Future[JsResult[String]] = {
    val authURL = BaseSpace.baseURLv1 + "/oauthv2/token"

    val authRequest: StandaloneWSRequest = ws.url(authURL)
      .withAuth(
        username = clientID,
        password = clientSecret,
        scheme   = WSAuthScheme.BASIC)
      .withQueryStringParameters(
        "code"         -> code,
        "redirect_uri" -> redirect,
        "grant_type"   -> "authorization_code"
      )

    // Return the future of a JSON result field validated as a string
    authRequest.post("").map { response =>
      (response.body[JsValue] \ "access_token").validate[String]
    }
  }
}

@Singleton
class BaseSpaceAPI @Inject() (
  val token        : String,
  ws               : StandaloneWSClient,
  implicit val mat : Materializer
) {
  /**
   * Wraps WSRequest to make queries to BaseSpace API
   *
   * Fills the authorization header with the access token and prefixes the
   * query path with the API base URL.
   *
   * @param path The URI of the desired resource, as documented in:
   *        https://developer.basespace.illumina.com/docs/content/documentation/rest-api/api-reference
   */
  def query(baseURL: BaseSpace.BaseURL)(path: String) : StandaloneWSRequest =
    ws.url(baseURL + "/" + path)
      .withQueryStringParameters(
        "access_token" -> token
      )

  def queryV1 = query(BaseSpace.baseURLv1)(_)
  def queryV2 = query(BaseSpace.baseURLv2)(_)

  /**
   * Returns the list of projects stored in BaseSpace.
   *
   * The returned value is a JsArray where every object describes a specific
   * project; in particular:
   *   - The "Id" attribute is the identifier of the project
   *   - The "Name" attribute is the title of the project
   */
  def projects() : Future[JsResult[JsArray]] = {
    queryV1("users/current/projects").get().map {
      response =>
        (response.body[JsValue] \ "Response" \ "Items").validate[JsArray]
    }
  }

  /**
   * Returns the list of samples saved in the project `projectId`.
   *
   * The returned value is a JsArray where every object describes a specific
   * sample; in particular:
   *   - The "Id" attribute is the identifier of the sample
   *   - The "Name" attribute is the name of the sample
   *
   * @param projectId The ID of the project whose samples will be listed.
   */
  def samples(projectId: String) : Future[JsResult[JsArray]] =
    queryV1(s"projects/$projectId/samples").get().map {
      response =>
        (response.body[JsValue] \ "Response" \ "Items").validate[JsArray]
    }

  /**
   * Returns the list of files linked to the sample `sampleId`.
   *
   * The returned value is a JsArray where every object describes a specific
   * file; in particular:
   *   - The "Id" attribute is the identifier of the file
   *   - The "Name" attribute is the name of the file
   *
   * @param sampleId The ID of the sample whose files will be listed.
   */
  def files(sampleId: String) : Future[JsResult[JsArray]] =
    queryV1(s"samples/$sampleId/files").get().map {
      response =>
        (response.body[JsValue] \ "Response" \ "Items").validate[JsArray]
    }

  /**
   * Downloads the file with ID `fileId`.
   *
   * @param fileId The BaseSpace ID of the file to be downloaded.
   * @param file The File where the contents of the download will be saved.
   */
   def downloadToFile(fileId: String, file: File) : Future[File] =
     queryV1(s"files/$fileId/content").withMethod("GET").stream() flatMap {
       response =>
       val outputStream = newOutputStream(file.toPath)

       // The sink that writes to the output stream
       val sink = Sink.foreach[ByteString] { bytes =>
         outputStream.write(bytes.toArray)
       }

       // Materialize and run the stream
       response.bodyAsSource.runWith(sink).andThen {
         case result =>
         // Close the output stream whether there was an error or not
         outputStream.close()
         // Get the result or rethrow the error
         result match {
           case Success(value) => value
           case Failure(error) => {
             // FIXME: Is rethrowing the error the best technique here?
             error
           }
         }
       }.map(_ => file)
     }
}
