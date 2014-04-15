/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package snap

import com.typesafe.sbtrc._
import com.typesafe.sbtrc.launching.SbtProcessLauncher
import akka.actor._
import java.io.File
import java.net.URLEncoder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import console.ClientController.HandleRequest
import JsonHelper._
import play.api.libs.json._
import play.api.libs.json.Json._
import play.api.libs.functional.syntax._
import java.util.concurrent.atomic.AtomicLong

sealed trait AppRequest

case class GetTaskActor(id: String, description: String, request: protocol.Request) extends AppRequest
case object GetWebSocketCreated extends AppRequest
case object CreateWebSocket extends AppRequest
case class NotifyWebSocket(json: JsObject) extends AppRequest
case object InitialTimeoutExpired extends AppRequest
case class ForceStopTask(id: String) extends AppRequest
case class UpdateSourceFiles(files: Set[File]) extends AppRequest
case class ProvisionSbtPool(instrumentation: String, originalMessage: GetTaskActor, sender: ActorRef) extends AppRequest

sealed trait AppReply

case class TaskActorReply(ref: ActorRef) extends AppReply
case object WebSocketAlreadyUsed extends AppReply
case class WebSocketCreatedReply(created: Boolean) extends AppReply

case class InspectRequest(json: JsValue)
object InspectRequest {
  val tag = "InspectRequest"

  implicit val inspectRequestReads: Reads[InspectRequest] =
    extractRequest[InspectRequest](tag)((__ \ "location").read[JsValue].map(InspectRequest.apply _))

  implicit val inspectRequestWrites: Writes[InspectRequest] =
    emitRequest(tag)(in => obj("location" -> in.json))

  def unapply(in: JsValue): Option[InspectRequest] = Json.fromJson[InspectRequest](in).asOpt
}

object AppActor {
  final val runTasks: Set[String] = Set(
    protocol.TaskNames.run,
    protocol.TaskNames.runMain,
    protocol.TaskNames.runEcho,
    protocol.TaskNames.runMainEcho)

  final val instrumentedRun: Map[String, String] = Map(
    protocol.TaskNames.run -> protocol.TaskNames.run,
    protocol.TaskNames.runMain -> protocol.TaskNames.runMain,
    protocol.TaskNames.runEcho -> protocol.TaskNames.run,
    protocol.TaskNames.runMainEcho -> protocol.TaskNames.runMain)

  def isRunRequest(request: protocol.Request): Boolean = request match {
    case protocol.GenericRequest(_, command, _) => runTasks(command)
    case _ => false
  }

  def getRunInstrumentation(request: protocol.Request): String = request match {
    case protocol.GenericRequest(_, command, params) if runTasks(command) =>
      params.get("instrumentation").asInstanceOf[Option[String]].map(Instrumentations.validate).getOrElse(Instrumentations.inspect)
    case _ => throw new RuntimeException(s"Cannot get instrumentation from a non-run request: $request")
  }

  def instrumentedRequest(request: protocol.Request): protocol.Request = request match {
    case r: protocol.GenericRequest =>
      if (isRunRequest(r)) r.copy(name = instrumentedRun(r.name))
      else r
    case r => r
  }
}

class AppActor(val config: AppConfig, val sbtProcessLauncher: SbtProcessLauncher) extends Actor with ActorLogging {
  import AppActor._

  AppManager.registerKeepAlive(self)

  def location = config.location

  val poolCounter = new AtomicLong(0)

  val uninstrumentedChildFactory = new DefaultSbtProcessFactory(location, sbtProcessLauncher)
  val uninstrumentedSbts = context.actorOf(Props(new ChildPool(uninstrumentedChildFactory)), name = "sbt-pool")

  private var instrumentedSbtPools: Map[String, ActorRef] = Map.empty[String, ActorRef]

  def addInstrumentedSbtPool(name: String, factory: SbtProcessFactory): Unit = {
    val n = name.trim()
    if (n != Instrumentations.inspect) {
      instrumentedSbtPools.get(n).foreach(_ ! PoisonPill)
      instrumentedSbtPools += (n -> context.actorOf(Props(new ChildPool(factory)), name = s"sbt-pool-$n-${poolCounter.getAndIncrement()}"))
    }
  }

  def getSbtPoolFor(name: String): Option[ActorRef] = name.trim() match {
    case Instrumentations.inspect => Some(uninstrumentedSbts)
    case n =>
      Instrumentations.validate(n)
      instrumentedSbtPools.get(n)
  }

  val socket = context.actorOf(Props(new AppSocketActor()), name = "socket")
  val projectWatcher = context.actorOf(Props(new ProjectWatcher(location, newSourcesSocket = socket, sbtPool = uninstrumentedSbts)),
    name = "projectWatcher")

  var webSocketCreated = false

  var tasks = Map.empty[String, ActorRef]

  context.watch(uninstrumentedSbts)
  context.watch(socket)
  context.watch(projectWatcher)

  // we can stay alive due to socket connection (and then die with the socket)
  // or else we just die after being around a short time
  context.system.scheduler.scheduleOnce(2.minutes, self, InitialTimeoutExpired)

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  override def receive = {
    case Terminated(ref) =>
      if (ref == uninstrumentedSbts || instrumentedSbtPools.values.exists(_ == ref)) {
        log.info(s"sbt pool terminated, killing AppActor ${self.path.name}")
        self ! PoisonPill
      } else if (ref == socket) {
        log.info(s"socket terminated, killing AppActor ${self.path.name}")
        self ! PoisonPill
      } else if (ref == projectWatcher) {
        log.info(s"projectWatcher terminated, killing AppActor ${self.path.name}")
        self ! PoisonPill
      } else {
        tasks.find { kv => kv._2 == ref } match {
          case Some((taskId, task)) =>
            log.debug("forgetting terminated task {} {}", taskId, task)
            tasks -= taskId
          case None =>
            log.warning("other actor terminated (why are we watching it?) {}", ref)
        }
      }

    case req: AppRequest => req match {
      case m @ GetTaskActor(taskId, description, request) if isRunRequest(request) =>
        val instrumentation = getRunInstrumentation(request)
        getSbtPoolFor(instrumentation) match {
          case None =>
            self ! ProvisionSbtPool(instrumentation, m, sender)
          case Some(pool) =>
            val task = context.actorOf(Props(new ChildTaskActor(taskId, description, pool)),
              name = "task-" + URLEncoder.encode(taskId, "UTF-8"))
            tasks += (taskId -> task)
            context.watch(task)
            log.debug("created task {} {}", taskId, task)
            sender ! TaskActorReply(task)
        }
      case ProvisionSbtPool(instrumentation, originalMessage, originalSender) =>
        getSbtPoolFor(instrumentation) match {
          case Some(_) =>
            self.tell(originalMessage, originalSender)
          case None =>
            instrumentation match {
              case Instrumentations.newRelic =>
                // Hack for demo purposes only.
                // The real solution would involve inspecting the project for a New Relic config file
                // and a TBD mechanism for getting the NR instrumentation jar.
                val nrConfigFile = new File(config.location, "conf/newrelic.yml")
                val nrJar = new File(config.location, "conf/newrelic.jar")
                val inst = NewRelic(nrConfigFile, nrJar)
                val processFactory = new DefaultSbtProcessFactory(location, sbtProcessLauncher, inst.jvmArgs)
                addInstrumentedSbtPool(Instrumentations.newRelic, processFactory)
                self.tell(originalMessage, originalSender)
            }
        }
      case GetTaskActor(taskId, description, _) =>
        val pool = uninstrumentedSbts
        val task = context.actorOf(Props(new ChildTaskActor(taskId, description, pool)),
          name = "task-" + URLEncoder.encode(taskId, "UTF-8"))
        tasks += (taskId -> task)
        context.watch(task)
        log.debug("created task {} {}", taskId, task)
        sender ! TaskActorReply(task)
      case GetWebSocketCreated =>
        sender ! WebSocketCreatedReply(webSocketCreated)
      case CreateWebSocket =>
        log.debug("got CreateWebSocket")
        if (webSocketCreated) {
          log.warning("Attempt to create websocket for app a second time {}", config.id)
          sender ! WebSocketAlreadyUsed
        } else {
          webSocketCreated = true
          socket.tell(GetWebSocket, sender)
        }
      case notify: NotifyWebSocket =>
        if (validateEvent(notify.json)) {
          socket.forward(notify)
        } else {
          log.error("Attempt to send invalid event {}", notify.json)
        }
      case InitialTimeoutExpired =>
        if (!webSocketCreated) {
          log.warning("Nobody every connected to {}, killing it", config.id)
          self ! PoisonPill
        }
      case ForceStopTask(id) =>
        tasks.get(id).foreach { ref =>
          log.debug("ForceStopTask for {} sending stop to {}", id, ref)
          ref ! ForceStop
        }
      case UpdateSourceFiles(files) =>
        projectWatcher ! SetSourceFilesRequest(files)
    }
  }

  private def validateEvent(json: JsObject): Boolean = {
    // we need either a toplevel "type" or a toplevel "taskId"
    // and then a nested "event" with a "type"
    val hasType = json \ "type" match {
      case JsString(t) => true
      case _ => false
    }
    val hasTaskId = json \ "taskId" match {
      case JsString(t) =>
        json \ "event" \ "type" match {
          case JsString(t) => true
          case _ => false
        }
      case _ => false
    }
    hasType || hasTaskId;
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    log.debug(s"preRestart, ${reason.getClass.getName}: ${reason.getMessage}, on $message")
  }

  override def postStop(): Unit = {
    log.debug("postStop")
  }

  // this actor corresponds to one protocol.Request, and any
  // protocol.Event that are associated with said request.
  // This is spawned from ChildTaskActor for each request.
  class ChildRequestActor(val requestor: ActorRef, val sbt: ActorRef, val request: protocol.Request) extends Actor with ActorLogging {
    sbt ! request

    override def receive = {
      case response: protocol.Response =>
        requestor.forward(response)
        // Response is supposed to arrive at the end,
        // after all Event
        log.debug("request responded to, request actor self-destructing")
        self ! PoisonPill
      case event: protocol.Event =>
        requestor.forward(event)
    }
  }

  private sealed trait ChildTaskRequest
  private case object ForceStop extends ChildTaskRequest

  // this actor's lifetime corresponds to one sequence of interactions with
  // an sbt instance obtained from the sbt pool.
  // It gets the pool from the app; reserves an sbt in the pool; and
  // forwards any messages you like to that pool.
  class ChildTaskActor(val taskId: String, val taskDescription: String, val pool: ActorRef) extends Actor {

    val reservation = SbtReservation(id = taskId, taskName = taskDescription)

    var requestSerial = 0
    def nextRequestName() = {
      requestSerial += 1
      "subtask-" + requestSerial
    }

    pool ! RequestAnSbt(reservation)

    private def handleRequest(requestor: ActorRef, sbt: ActorRef, request: protocol.Request) = {
      context.actorOf(Props(new ChildRequestActor(requestor = requestor,
        sbt = sbt, request = request)), name = nextRequestName())
    }

    private def errorOnStopped(requestor: ActorRef, request: protocol.Request) = {
      requestor ! protocol.ErrorResponse(s"Task has been stopped (task ${reservation.id} request ${request})")
    }

    private def handleTerminated(ref: ActorRef, sbtOption: Option[ActorRef]): Unit = {
      if (Some(ref) == sbtOption) {
        log.debug("sbt actor died, task actor self-destructing")
        self ! PoisonPill // our sbt died
      }
    }

    override def receive = gettingReservation(Nil)

    private def gettingReservation(requestQueue: List[(ActorRef, protocol.Request)]): Receive = {
      case req: ChildTaskRequest => req match {
        case ForceStop =>
          pool ! ForceStopAnSbt(reservation.id) // drops our reservation
          requestQueue.reverse.foreach(tuple => errorOnStopped(tuple._1, tuple._2))
          context.become(forceStopped(None))
      }
      case req: protocol.Request =>
        context.become(gettingReservation((sender, req) :: requestQueue))
      case SbtGranted(filled) =>
        val sbt = filled.sbt.getOrElse(throw new RuntimeException("we were granted a reservation with no sbt"))
        // send the queue
        requestQueue.reverse.foreach(tuple => handleRequest(tuple._1, sbt, tuple._2))

        // monitor sbt death
        context.watch(sbt)
        // now enter have-sbt mode
        context.become(haveSbt(sbt))

      // when we die, the reservation should be auto-released by ChildPool
    }

    private def haveSbt(sbt: ActorRef): Receive = {
      case req: protocol.Request => handleRequest(sender, sbt, req)
      case ForceStop => {
        pool ! ForceStopAnSbt(reservation.id)
        context.become(forceStopped(Some(sbt)))
      }
      case Terminated(ref) => handleTerminated(ref, Some(sbt))
    }

    private def forceStopped(sbtOption: Option[ActorRef]): Receive = {
      case req: protocol.Request => errorOnStopped(sender, req)
      case Terminated(ref) => handleTerminated(ref, sbtOption)
      case SbtGranted(filled) =>
        pool ! ReleaseAnSbt(reservation.id)
    }
  }

  class AppSocketActor extends WebSocketActor[JsValue] with ActorLogging {
    override def onMessage(json: JsValue): Unit = {
      json match {
        case InspectRequest(m) => for (cActor <- consoleActor) cActor ! HandleRequest(json)
        case WebSocketActor.Ping(ping) => produce(WebSocketActor.Pong(ping.cookie))
        case _ => log.info("unhandled message on web socket: {}", json)
      }
    }

    override def subReceive: Receive = {
      case NotifyWebSocket(json) =>
        log.debug("sending message on web socket: {}", json)
        produce(json)
    }

    override def postStop(): Unit = {
      log.debug("postStop")
    }
  }
}
