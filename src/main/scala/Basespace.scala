package era7bio.basespace

import DataFormatImplicits._
import play.api.libs.ws.{WSAuthScheme, WSClient, WSRequest}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits._

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

  /** Helper to asynchronously retrieve data which involves pagination
    * (i.e. projects, datasets, samples, etc), passing a method to extract
    * the `TotalCount` field from the JSON and a method to extract the items
    * per se.
    */
  private def pagination[A](
      request: WSRequest,
      getNumItems: JsValue => JsLookupResult,
      getItems: JsValue => JsLookupResult,
      concatenateOp: A => A => A,
      init: A)(implicit rds: Reads[A]): Future[JsError + A] = {

    val limit = 1024
    // Retrieve all data (until limit) from a given offset
    def retrieveFromOffset(offset: Int): Future[JsError + A] =
      request
        .withQueryString(
          "limit"  -> limit.toString,
          "offset" -> offset.toString
        )
        .get()
        .map { response =>
          getItems(response.json).validate[A] match {
            case success: JsSuccess[A] => Right(success.get)
            case error: JsError        => Left(error)
          }
        }

    def numItems: Future[JsError + Int] =
      request.get.map { response =>
        getNumItems(response.json).validate[JsNumber] match {
          case success: JsSuccess[JsNumber] => Right(success.get.as[Int])
          case error: JsError               => Left(error)
        }
      }

    // If could not retrieve num of items, return JsError, else do async
    // requests to retrieve all pages of data in chunks of size {limit}
    // and concatenate them
    numItems.flatMap { maybeNum =>
      maybeNum.fold[Future[JsError + A]](
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

              // Aux method to concatenate JsArrays, Seqs, etc
              @annotation.tailrec
              def concatenate(current: Int,
                              until: Int,
                              prev: A): (JsError + A) =
                if (current < until) {
                  val query = results(current)

                  if (query.isRight)
                    concatenate(current + 1,
                                until,
                                concatenateOp(query.right.get)(prev))
                  else
                    results(current)
                } else
                  Right(prev)

              concatenate(0, numPages, init)
          }
        }
      )
    }
  }

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
    * Returns whether the token introduced is valid
    *
    * The returned value is a True iff the token has access to Basespace
    */
  def isTokenValid: Future[Boolean] = {
    val request = queryV1("users/current")

    request
      .get()
      .map { response =>
        (response.json \ "Response").isDefined
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
  def projects: Future[JsError + Seq[Project]] = {
    val request = queryV1("users/current/projects")

    pagination[Seq[Project]](
      request,
      { _ \ "Response" \ "TotalCount" },
      { _ \ "Response" \ "Items" }, { a => b =>
        a ++ b
      },
      Seq.empty
    )
  }

  /**
    * Returns the list of projects from Basespace which contain importable datasets
    * (where an importable dataset is the one with type different from common.files)
    *
    * @param maxSize for the datasets in each project in bytes
    */
  def importableProjects(maxSize: Long): Future[JsError + Seq[Project]] =
    projects.flatMap { result =>
      result.fold(
        error => Future.successful { Left(error) },
        projects => {
          Future
            .sequence(
              projects.toList.map { project =>
                val id = project.id

                importableProjectDatasets(id, maxSize).map { result =>
                  result.fold(
                    error => (project, 0),
                    datasets => (project, datasets.length)
                  )
                }
              }
            )
            .map { result =>
              val projects = result.collect {
                case (project, numImportable) if numImportable > 0 =>
                  project.copy(importableDatasets = Some(numImportable))
              }

              Right(projects)
            }
        }
      )
    }

  def project(projectID: String): Future[JsError + Project] =
    queryV2(s"projects/$projectID").get().map { response =>
      response.json.validate[Project] match {
        case success: JsSuccess[Project] => Right(success.get)
        case error: JsError              => Left(error)
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
  def samples(projectId: String): Future[JsError + JsArray] = {
    val request = queryV1(s"projects/$projectId/samples")

    pagination[JsArray](
      request,
      { _ \ "Response" \ "TotalCount" },
      { _ \ "Response" \ "Items" }, { a => b =>
        a ++ b
      },
      new JsArray
    )
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
  def files(sampleId: String): Future[JsError + Seq[BasespaceFile]] = {
    val request = queryV1(s"samples/$sampleId/files")

    pagination[Seq[BasespaceFile]](
      request,
      { _ \ "Response" \ "TotalCount" },
      { _ \ "Response" \ "Items" }, { a => b =>
        a ++ b
      },
      Seq.empty
    )
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
  def datasets: Future[JsError + Seq[Dataset]] = {
    val request = queryV2("datasets")

    pagination[Seq[Dataset]](
      request,
      { _ \ "Paging" \ "TotalCount" },
      { _ \ "Items" }, { a => b =>
        a ++ b
      },
      Seq.empty
    )
  }

  /**
    * Returns all the datasets which have type different from `common.files`
    * and whose length is below `maxSize`
    *
    * @param maxSize maximum size in bytes for a dataset
    */
  def importableDatasets(maxSize: Long): Future[JsError + Seq[Dataset]] =
    datasets.map {
      _.right.map { datasets =>
        datasets.filter { dataset =>
          dataset.datasetType != "common.files" && dataset.size <= maxSize
        }
      }
    }

  /**
    * List all the datasets for a project
    */
  def projectDatasets(projectID: String): Future[JsError + Seq[Dataset]] = {
    val request = queryV2(s"projects/$projectID/datasets")

    pagination[Seq[Dataset]](
      request,
      { _ \ "Paging" \ "TotalCount" },
      { _ \ "Items" }, { a => b =>
        a ++ b
      },
      Seq.empty
    )
  }

  /**
    * List all the datasets for a project which are not of type `common.files` and
    * whose size is below a certain threshold
    *
    * @param projectID the id of the project we want to retrieve the datasets for
    * @param maxSize the maximum size in bytes for each dataset in the project
    */
  def importableProjectDatasets(projectID: String,
                                maxSize: Long): Future[JsError + Seq[Dataset]] =
    projectDatasets(projectID).map { result =>
      result.right.map { datasets =>
        datasets.filter { dataset =>
          dataset.datasetType != "common.files" && dataset.size <= maxSize
        }
      }
    }

  /**
    * Returns the information for a dataset
    *
    * @param datasetID the id of the dataset
    */
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
    // Get the dataset name first, to set it in the files retrieved for that dataset,
    // then get the basespace files associated to that dataset
    dataset(datasetID).flatMap { datasetResult =>
      datasetResult.fold[Future[JsError + Seq[BasespaceFile]]](
        { error =>
          Future.successful { Left(error) }
        }, { data =>
          val datasetName = data.name

          val request = queryV2(s"datasets/$datasetID/files")
            .withQueryString("filehrefcontentresolution" -> "true")

          pagination[Seq[BasespaceFile]](
            request,
            { _ \ "Paging" \ "TotalCount" },
            { _ \ "Items" }, { a => b =>
              a ++ b
            },
            Seq.empty
          ).map { basespaceData =>
            basespaceData.right.map { files =>
              files.map { file =>
                file.copy(datasetName = Some(datasetName))
              }
            }
          }
        }
      )
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
}
