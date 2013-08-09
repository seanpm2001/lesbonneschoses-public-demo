package controllers

import play.api._
import play.api.mvc._

import play.api.libs.ws._
import play.api.libs.json._

import scala.concurrent._
import play.api.libs.concurrent.Execution.Implicits._

import Play.current

import io.prismic._

/**
 * This Prismic object contains several helper to make it easier
 * to build your application using both Prismic.io and Play:
 *
 * - 
 */
object Prismic extends Controller {

  // -- Define the key name to use for storing the Prismic.io access token into the Play session
  private val ACCESS_TOKEN = "ACCESS_TOKEN"

  // -- Cache to use (default to keep 200 JSON responses in a LRU cache)
  private val Cache = BuiltInCache(200)

  // -- Write debug and error messages to the Play `prismic` logger (check the configuration in application.conf)
  private val Logger = (level: String, message: String) => level match { 
    case "DEBUG" => play.api.Logger("prismic").debug(message)
    case "ERROR" => play.api.Logger("prismic").error(message)
    case _ =>
  }
  
  // Helper method to read the Play application configuration
  private def config(key: String) = Play.configuration.getString(key).getOrElse(sys.error(s"Missing configuration [$key]"))

  private def callbackUrl(implicit rh: RequestHeader) = routes.Prismic.callback(code = None).absoluteURL()

  // -- Define a `Prismic request` that contain both the original request and the Prismic call context
  case class Request[A](request: play.api.mvc.Request[A], ctx: Context) extends WrappedRequest(request)

  // -- Action builder
  def action[A](bodyParser: BodyParser[A])(ref: Option[String] = None)(block: Prismic.Request[A] => Future[SimpleResult]) = Action.async(bodyParser) { request =>
    (
      for {
        api <- apiHome(request.session.get(ACCESS_TOKEN))
        ctx = Context(api, ref.getOrElse(api.master.ref), request.session.get(ACCESS_TOKEN), Application.linkResolver(api, ref.filterNot(_ == api.master.ref))(request))
        result <- block(Request(request, ctx))
      } yield result
    ).recover {
      case ApiError(Error.INVALID_TOKEN, _) => Redirect(routes.Prismic.signin).withNewSession
      case ApiError(Error.AUTHORIZATION_NEEDED, _) => Redirect(routes.Prismic.signin).withNewSession
    }
  }

  // -- Alternative action builder for the default body parser
  def action(ref: Option[String] = None)(block: Prismic.Request[AnyContent] => Future[SimpleResult]): Action[AnyContent] = action(parse.anyContent)(ref)(block)

  def ctx(implicit req: Request[_]) = req.ctx

  // -- Contexts

  case class Context(api: Api, ref: String, accessToken: Option[String], linkResolver: DocumentLinkResolver) {
    def maybeRef = Option(ref).filterNot(_ == api.master.ref)
    def hasPrivilegedAccess = accessToken.isDefined
  }
  
  object Context {
    implicit def fromRequest(implicit req: Request[_]): Context = req.ctx
  }
  
  // -- API

  def apiHome(accessToken: Option[String] = None) = Api.get(config("prismic.api"), accessToken = accessToken, cache = Cache, logger = Logger)

  // -- OAuth actions
  
  def signin = Action.async { implicit req =>
    for(api <- apiHome()) yield {
      Redirect(api.oauthInitiateEndpoint, Map(
        "client_id" -> Seq(config("prismic.clientId")),
        "redirect_uri" -> Seq(callbackUrl),
        "scope" -> Seq("master+releases")
      ))
    }
  }

  def signout = Action {
    Redirect(routes.Application.index(ref = None)).withNewSession
  }

  def callback(code: Option[String]) = Action.async { implicit req =>
    (
      for {
        api <- apiHome()
        tokenResponse <- WS.url(api.oauthTokenEndpoint).post(Map(
          "grant_type" -> Seq("authorization_code"),
          "code" -> Seq(code.get),
          "redirect_uri" -> Seq(callbackUrl),
          "client_id" -> Seq(config("prismic.clientId")),
          "client_secret" -> Seq(config("prismic.clientSecret"))
        )).filter(_.status == 200).map(_.json)
      } yield { 
        Redirect(routes.Application.index(ref = None)).withSession(
          ACCESS_TOKEN -> (tokenResponse \ "access_token").as[String]
        )
      }
    ).recover {
      case x: Throwable => 
        Logger("ERROR", s"""Can't retrieve the OAuth token for code $code: ${x.getMessage}""".stripMargin)
        Unauthorized("Can't sign you in")
    }
  }

}