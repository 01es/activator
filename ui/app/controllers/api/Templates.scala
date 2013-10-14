/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package controllers.api

import play.api.mvc._
import play.api.libs.json._
import activator._
import activator.cache.TemplateMetadata
import scala.concurrent.duration._
import scala.concurrent.Future

object Templates extends Controller {
  // This will load our template cache and ensure all templates are available for the demo.
  // We should think of an alternative means of loading this in the future.
  // TODO - We should load timeout from configuration.
  implicit val timeout = akka.util.Timeout(Duration(12, SECONDS))
  val templateCache = activator.UICacheHelper.makeDefaultCache(snap.Akka.system)

  // Here's the JSON rendering of template metadata.
  implicit object Protocol extends Format[TemplateMetadata] {
    def writes(o: TemplateMetadata): JsValue =
      JsObject(
        List("id" -> JsString(o.id),
          "name" -> JsString(o.name),
          "title" -> JsString(o.title),
          "timestamp" -> JsNumber(o.timeStamp),
          "description" -> JsString(o.description),
          "tags" -> JsArray(o.tags map JsString.apply)))
    //We don't need reads, really
    def reads(json: JsValue): JsResult[TemplateMetadata] =
      JsError("Reading TemplateMetadata not supported!")
  }

  def list = Action.async { request =>
    import concurrent.ExecutionContext.Implicits._
    templateCache.metadata map { m => Ok(Json toJson m) }
  }

  def tutorial(id: String, location: String) = Action.async { request =>
    import concurrent.ExecutionContext.Implicits._
    templateCache tutorial id map { tutorialOpt =>
      // TODO - Use a Validation  applicative functor so this isn't so ugly.
      val result =
        for {
          tutorial <- tutorialOpt
          file <- tutorial.files get location
        } yield file
      result match {
        case Some(file) => Ok sendFile file
        case _ => NotFound
      }
    }
  }

  // this is not a controller method, also invoked by HomePageActor
  def doCloneTemplate(templateId: String, location: java.io.File, name: Option[String]): Future[ProcessResult[Unit]] = {
    import scala.concurrent.ExecutionContext.Implicits._
    // for now, hardcode that we always filter metadata if it is NOT a templateTemplate, and
    // never do if it is a templateTemplate. this may be a toggle in the UI somehow later.
    templateCache.template(templateId) flatMap { templateOpt =>
      activator.cache.Actions.cloneTemplate(
        templateCache,
        templateId,
        location,
        name,
        filterMetadata = !templateOpt.map(_.metadata.templateTemplate).getOrElse(false),
        additionalFiles = UICacheHelper.scriptFilesForCloning)
    }
  }

  def cloneTemplate = Action.async(parse.json) { request =>
    val location = new java.io.File((request.body \ "location").as[String])
    val templateid = (request.body \ "template").as[String]
    val name = (request.body \ "name").asOpt[String]
    import scala.concurrent.ExecutionContext.Implicits._
    val result = doCloneTemplate(templateid, location, name)
    result.map(x => Ok(request.body)).recover {
      case e => NotAcceptable(e.getMessage)
    }
  }
}
