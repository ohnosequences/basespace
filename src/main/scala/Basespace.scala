package era7bio.basespace

import DataFormatImplicits._
import play.api.libs.ws.{WSAuthScheme, WSClient, WSRequest}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.util.ByteString
import akka.stream.scaladsl.Sink
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits._
import scala.util.{Failure, Success}
import javax.inject.{Inject, Singleton}
import java.io.File
import java.nio.file.Files.newOutputStream

class BaseSpaceAuth(
    val clientID: String,
    val clientSecret: String,
    ws: WSClient
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
      "?client_id=" + clientID +
      "&redirect_uri=" + redirect +
      "&response_type=" + "code" +
      "&scope=" + scope

  /**
    * Performs POST request to exchange authentication code for access token.
    *
    * @param redirect The user will be redirected to this URL when the
    *                 authentication process finishes.
    * @param code     The authentication code received after visiting
    *                 [[accessURL]].
    */
  def authenticate(redirect: String, code: String): Future[JsResult[String]] = {
    val authURL = baseURLv1 + "/oauthv2/token"

    val authRequest: WSRequest = ws
      .url(authURL)
      .withAuth(username = clientID,
                password = clientSecret,
                scheme = WSAuthScheme.BASIC)
      .withQueryString(
        "code"         -> code,
        "redirect_uri" -> redirect,
        "grant_type"   -> "authorization_code"
      )

    // Return the future of a JSON result field validated as a string
    authRequest.post("").map { response =>
      (response.json \ "access_token").validate[String]
    }
  }
}

class BaseSpaceAPI(
    val token: String,
    ws: WSClient
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
  def query(baseURL: BaseURL)(path: String): WSRequest =
    ws.url(baseURL + "/" + path)
      .withQueryString(
        "access_token" -> token
      )

  def queryV1 = query(baseURLv1)(_)
  def queryV2 = query(baseURLv2)(_)

  /**
    * Returns the number of projects available in Basespace
    */
  def numProjects: Future[JsError + Int] =
    queryV1("users/current/projects").get.map { response =>
      (response.json \ "Response" \ "TotalCount").validate[JsNumber] match {
        case success: JsSuccess[JsNumber] => Right(success.get.as[Int])
        case error: JsError               => Left(error)
      }
    }

  /**
    * Returns the list of projects stored in BaseSpace.
    *
    * The returned value is a JsArray where every object describes a specific
    * project; in particular:
    *   - The "Id" attribute is the identifier of the project
    *   - The "Name" attribute is the title of the project
    */
  def projects: Future[JsError + JsArray] = {
    val limit = 1024

    def retrieveFromOffset(offset: Int): Future[JsError + JsArray] =
      queryV1("users/current/projects")
        .withQueryString(
          "limit"  -> limit.toString,
          "offset" -> offset.toString
        )
        .get()
        .map { response =>
          (response.json \ "Response" \ "Items").validate[JsArray] match {
            case success: JsSuccess[JsArray] => Right(success.get)
            case error: JsError              => Left(error)
          }
        }

    numProjects.flatMap { maybeNum =>
      maybeNum.fold[Future[JsError + JsArray]](
        { error =>
          Future.successful { Left(error) }
        }, { projectCount =>
          val numPages = math.ceil((projectCount * 1.0) / limit).toInt

          val queries = Future.sequence(
            List.tabulate(numPages) { i =>
              retrieveFromOffset(i)
            }
          )

          queries.map {
            completed =>
              val results = completed.toArray

              @annotation.tailrec
              def concatenateJsArrays(current: Int,
                                      until: Int,
                                      prev: JsArray): (JsError + JsArray) =
                if (current < until) {
                  val query = results(current)

                  if (query.isRight)
                    concatenateJsArrays(current + 1,
                                        until,
                                        query.right.get ++ prev)
                  else
                    results(current)
                } else
                  Right(prev)

              concatenateJsArrays(0, numPages, new JsArray)
          }
        }
      )
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
  def samples(projectId: String): Future[JsError + JsArray] =
    queryV1(s"projects/$projectId/samples").get().map { response =>
      (response.json \ "Response" \ "Items").validate[JsArray] match {
        case success: JsSuccess[JsArray] => Right(success.get)
        case error: JsError              => Left(error)
      }
    }

  def file(fileID: String): Future[JsError + BasespaceFile] =
    queryV1(s"files/$fileID")
      .withQueryString("filehrefcontentresolution" -> "true")
      .get()
      .map { response =>
        (response.json \ "Response").validate[BasespaceFile] match {
          case success: JsSuccess[BasespaceFile] => Right(success.get)
          case error: JsError                    => Left(error)
        }
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
  def files(sampleId: String): Future[JsError + Seq[BasespaceFile]] =
    queryV1(s"samples/$sampleId/files").get().map { response =>
      (response.json \ "Response" \ "Items")
        .validate[Seq[BasespaceFile]] match {
        case success: JsSuccess[Seq[BasespaceFile]] => Right(success.get)
        case error: JsError                         => Left(error)
      }
    }

  /**
    * Returns an array of all the biosamples
    */
  def biosamples(): Future[JsError + Seq[Biosample]] =
    queryV2("biosamples").get().map { response =>
      (response.json \ "Items").validate[Seq[Biosample]] match {
        case success: JsSuccess[Seq[Biosample]] => Right(success.get)
        case error: JsError                     => Left(error)
      }
    }

  /**
    * Returns an array of all the datasets
    */
  def datasets(maxDatasetsListSize: Int = 10): Future[JsError + Seq[Dataset]] =
    queryV2("datasets")
      .withQueryString(
        "limit" -> maxDatasetsListSize.toString()
      )
      .get()
      .map { response =>
        (response.json \ "Items").validate[Seq[Dataset]] match {
          case success: JsSuccess[Seq[Dataset]] => Right(success.get)
          case error: JsError                   => Left(error)
        }
      }

  /**
    * List all the datasets for a project
    */
  def projectDatasets(projectID: String): Future[JsError + Seq[Dataset]] =
    queryV2(s"projects/$projectID/datasets")
      .get()
      .map { response =>
        (response.json \ "Items").validate[Seq[Dataset]] match {
          case success: JsSuccess[Seq[Dataset]] => Right(success.get)
          case error: JsError                   => Left(error)
        }
      }

  def dataset(datasetID: String): Future[JsError + Dataset] =
    queryV2(s"datasets/$datasetID").get().map { response =>
      response.json.validate[Dataset] match {
        case success: JsSuccess[Dataset] => Right(success.get)
        case error: JsError              => Left(error)
      }
    }

  /**
    * Returns a list of all the files that belong to the dataset datasetID
    * @param datasetID The ID of the dataset whose files will be listed
    */
  def datasetFiles(datasetID: String): Future[JsError + Seq[BasespaceFile]] =
    queryV2(s"datasets/$datasetID/files")
      .withQueryString("filehrefcontentresolution" -> "true")
      .get()
      .flatMap { response =>
        ((response.json \ "Items").validate[Seq[BasespaceFile]] match {
          case error: JsError => Left(error)
          case success: JsSuccess[Seq[BasespaceFile]] =>
            Right {
              dataset(datasetID) map { datasetResponse =>
                val name = datasetResponse match {
                  case Left(_)        => None
                  case Right(dataset) => Some(dataset.name)
                }
                success.get map (_.copy(datasetName = name))
              }
            }
        }) match {
          case Left(s)  => Future.successful(Left(s))
          case Right(f) => f.map(Right(_))
        }
      }

  /**
    * Returns a list of the files whose extension is .fastq.gz that belong to
    * the dataset datasetID
    * @param datasetID The ID of the dataset whose files will be listed
    */
  def datasetFASTQFiles(
      datasetID: String): Future[JsError + Seq[BasespaceFile]] =
    datasetFiles(datasetID).map[JsError + Seq[BasespaceFile]] { response =>
      response.right map { files =>
        files.filter { file =>
          file.name.matches(".*\\.fastq\\.gz$")
        }
      }
    }

  /**
    * Returns paired fastq files for a dataset in (R1, R2) format.
    * If the pair for a R1 file (R2, resp.) does not exist, then we return None
    * @param datasetID The ID of the dataset whose files will be listed
    */
  def datasetPairedFASTQ(
      datasetID: String): Future[JsError + Option[PairedFASTQ]] =
    datasetFiles(datasetID).map[JsError + Option[PairedFASTQ]] { response =>
      val r1suffix = "R1_001.fastq.gz"
      val r2suffix = "R2_001.fastq.gz"

      response.right map { files =>
        val fastqs = files.filter { file =>
          file.name.matches(".*\\.fastq\\.gz$")
        }

        val maybeR1 = fastqs.find { file =>
          file.name.endsWith(r1suffix)
        }

        maybeR1.flatMap { r1 =>
          val r2name = r1.name.stripSuffix(r1suffix) ++ r2suffix

          val maybeR2 = fastqs.find { file =>
            file.name == r2name
          }

          maybeR2.map { r2 =>
            PairedFASTQ(r1, r2)
          }
        }
      }
    }

  /**
    * Transforms a Seq[JsError + Seq[BasespaceFile]] into a
    * JsError + Seq[BasespaceFile], returning the flattened sequence of
    * sequences of basespace files if and only if there is no Left(JsError) in
    * the outer sequence.
    * @type {[type]}
    */
  private val checkSeqFiles: Seq[BasespaceFile] => Seq[
    Either[JsError, Seq[BasespaceFile]]] => Either[JsError,
                                                   Seq[BasespaceFile]] =
    goodSeq =>
      seq => {
        seq match {
          case Left(err) :: xs =>
            Left(err)
          case Right(values) :: xs =>
            checkSeqFiles(goodSeq ++ values)(xs)
          case Nil =>
            Right(goodSeq)
        }
    }

  def allFASTQfiles(
      maxDatasetsListSize: Int = 10): Future[JsError + Seq[BasespaceFile]] =
    datasets(maxDatasetsListSize) flatMap { datasets =>
      datasets match {
        case Left(error) => Future(Left(error))
        case Right(seq) =>
          Future.sequence(
            seq map { dataset =>
              datasetFASTQFiles(dataset.id)
            }
          ) map checkSeqFiles(Seq())
      }
    }

  /**
    * Returns all the datasets associated with biosample `biosampleID`.
    *
    * @param bioSampleID The ID of the biosample whose datasets are returned.
    */
  def biosampleDatasets(biosampleID: String) =
    queryV2("datasets")
      .withQueryString("inputbiosamples" -> biosampleID)
      .get()
      .map { response =>
        (response.json \ "Items").validate[JsArray]
      }
}
