/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator

import xsbti.AppConfiguration
import activator.properties.ActivatorProperties.SCRIPT_NAME
import activator.cache.Actions.cloneTemplate
import java.io.File
import sbt.complete.{ Parser, Parsers }
import scala.concurrent.{ TimeoutException, Await }
import scala.concurrent.duration._
import akka.actor.ActorSystem
import scala.Some
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._

object ActivatorCli {
  def apply(configuration: AppConfiguration): Int = try withContextClassloader {
    System.out.println()
    val name = getApplicationName()

    val akkaConfig = ConfigFactory.parseMap(Map("akka.log-dead-letters" -> "off", "akka.log-dead-letters-during-shutdown" -> "off").asJava)

    val system = ActorSystem("default", akkaConfig)
    val projectDir = new File(name).getAbsoluteFile
    // Ok, now we load the template cache...

    val defaultDuration: FiniteDuration = sys.props.get("activator.timeout").flatMap { timeoutString =>
      val duration = Duration(timeoutString)
      if (duration.isFinite()) {
        Some(FiniteDuration(duration.length, duration.unit))
      } else {
        None
      }
    } getOrElse Duration(10, SECONDS)

    implicit val timeout = akka.util.Timeout(defaultDuration)

    // Create our default cache
    // TODO - move this into a common shared location between CLI and GUI.
    val cache = UICacheHelper.makeDefaultCache(system)
    // Get all possible names.
    // TODO - Drive this whole thing through futures, if we feel SAUCY enough, rather than waiting for results.
    System.out.println()
    System.out.println("Fetching the latest list of templates...")
    System.out.println()
    val metadata = try {
      Await.result(cache.metadata, defaultDuration)
    } catch {
      case e: TimeoutException =>
        // fall back to just using whatever we have in the local cache
        System.out.println()
        System.out.println("Could not fetch the updated list of templates.  Using the local cache.")
        System.out.println("Check your proxy settings or increase the timeout.  For more details see:\nhttp://typesafe.com/activator/docs")
        System.out.println()

        val localOnlyCache = UICacheHelper.makeLocalOnlyCache(ActorSystem("fallback", akkaConfig))
        Await.result(localOnlyCache.metadata, defaultDuration)
    }
    val templateNames = metadata.map(_.name).toSeq.distinct
    System.out.println()
    System.out.println(s"The new application will be created in ${projectDir.getAbsolutePath}")
    System.out.println()
    val templateName = getTemplateName(templateNames)
    // Check validity, and check for direct match first
    val template = (metadata.find(_.name == templateName) orElse
      metadata.find(_.name.toLowerCase contains templateName.toLowerCase))
    template match {
      case Some(t) =>
        System.out.println(s"""OK, application "$name" is being created using the "${t.name}" template.""")
        System.out.println()
        import scala.concurrent.ExecutionContext.Implicits.global

        // record stats in parallel while we are cloning
        val statsRecorded = TemplatePopularityContest.recordClonedIgnoringErrors(t.name)

        // TODO - Is this duration ok?
        Await.result(
          cloneTemplate(
            cache,
            t.id,
            projectDir,
            Some(name),
            filterMetadata = !t.templateTemplate,
            additionalFiles = UICacheHelper.scriptFilesForCloning),
          Duration(5, MINUTES))
        printUsage(name, projectDir)

        // don't wait too long on this remote call, we ignore the
        // result anyway; just don't want to exit the JVM too soon.
        Await.result(statsRecorded, Duration(5, SECONDS))

        0
      case _ =>
        sys.error("Could not find template with name: $templateName")
    }
  } catch {
    case e: Exception =>
      System.err.println(e.getMessage)
      e.printStackTrace()
      1
  }

  private def printUsage(name: String, dir: File): Unit = {
    // TODO - Cross-platform-ize these strings! Possibly keep script name in SnapProperties.
    System.out.println(s"""|To run "$name" from the command-line, run:
                           |${dir.getAbsolutePath}/${SCRIPT_NAME} run
                           |
                           |To run the test for "$name" from the command-line, run:
                           |${dir.getAbsolutePath}/${SCRIPT_NAME} test
                           |
                           |To run the Activator UI for "$name" from the command-line, run:
                           |${dir.getAbsolutePath}/${SCRIPT_NAME} ui
                           |""".stripMargin)
  }

  private def getApplicationName(): String = {
    System.out.println("Enter an application name")
    val appNameParser: Parser[String] = {
      import Parser._
      import Parsers._
      token(any.* map { _ mkString "" }, "<application name>")
    }

    readLine(appNameParser) filterNot (_.isEmpty) getOrElse sys.error("No application name specified.")
  }

  private def getTemplateName(possible: Seq[String]): String = {
    val templateNameParser: Parser[String] = {
      import Parser._
      import Parsers._
      token(any.* map { _ mkString "" }, "<template name>").examples(possible.toSet, false)
    }
    System.out.println("Browse the list of templates: http://typesafe.com/activator/templates")
    System.out.println("Enter a template name, or hit tab to see a list")
    readLine(templateNameParser) filterNot (_.isEmpty) getOrElse sys.error("No template name specified.")
  }

  /** Uses SBT complete library to read user input with a given auto-completing parser. */
  private def readLine[U](parser: Parser[U], prompt: String = "> ", mask: Option[Char] = None): Option[U] = {
    val reader = new sbt.FullReader(None, parser)
    reader.readLine(prompt, mask) flatMap { line =>
      val parsed = Parser.parse(line, parser)
      parsed match {
        case Right(value) => Some(value)
        case Left(e) => None
      }
    }
  }
  def withContextClassloader[A](f: => A): A = {
    val current = Thread.currentThread
    val old = current.getContextClassLoader
    current setContextClassLoader getClass.getClassLoader
    try f
    finally current setContextClassLoader old
  }
}
