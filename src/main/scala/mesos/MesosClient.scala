package mesos

import java.net.URI
import java.util.Optional

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.MediaType.Compressible
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpRequest, HttpResponse, MediaType}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshal, Unmarshaller}
import akka.pattern.pipe
import akka.stream.alpakka.recordio.scaladsl.RecordIOFraming
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{ActorMaterializer, Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import org.apache.mesos.v1.Protos.{AgentID, ExecutorID, FrameworkID, FrameworkInfo, Offer, OfferID, TaskID, TaskInfo, TaskState, TaskStatus}
import org.apache.mesos.v1.scheduler.Protos.Call._
import org.apache.mesos.v1.scheduler.Protos.Event._
import org.apache.mesos.v1.scheduler.Protos.{Call, Event}

import scala.collection.JavaConverters.{asScalaBuffer, seqAsJavaList, _}
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
/**
  * Created by tnorris on 6/4/17.
  */

//control messages
case class SubmitTask(task:TaskInfo)
case class DeleteTask(taskId:String)
case class Reconcile(tasks:Iterable[TaskRecoveryDetail])
case object Subscribe
case object Teardown
//events
case object TeardownComplete
case class TaskRecoveryDetail(taskId:String, agentId:String)

//TODO: mesos authentication
class MesosClientActor (val id:String, val frameworkName:String, val master:String, val role:String, val taskMatcher:(String, Iterable[TaskInfo], Iterable[Offer]) => Map[OfferID,Seq[TaskInfo]])
  extends Actor with ActorLogging {
  implicit val ec:ExecutionContext = context.dispatcher
  implicit val system:ActorSystem = context.system
  implicit val materializer:ActorMaterializer = ActorMaterializer()(system)

  private var streamId:String = null

  private val frameworkID = FrameworkID.newBuilder().setValue(id).build();
  private val cpusPerTask = 0.1
  //TODO: handle redirect to master see https://github.com/mesosphere/mesos-rxjava/blob/d6fd040af3322552012fb3dcf61debb9886adbf3/mesos-rxjava-client/src/main/java/com/mesosphere/mesos/rx/java/MesosClient.java#L167
  private val mesosUri = URI.create(s"${master}/api/v1/scheduler")
  private var pendingTaskInfo:Map[TaskID,TaskInfo] = Map()
  private var pendingTaskPromises:Map[TaskID,Promise[TaskStatus]] = Map()
  private var deleteTaskPromises:Map[TaskID, Promise[TaskStatus]] = Map()
  private var taskStatuses:Map[TaskID, TaskStatus] = Map()

  //TODO: FSM for handling subscribing, subscribed, failed, etc states
  override def receive: Receive = {
    //control messages
    case Subscribe => {
      subscribe(self, frameworkID, frameworkName)
    }
    case SubmitTask(task) => {
      val taskPromise = Promise[TaskStatus]()
      pendingTaskPromises += (task.getTaskId -> taskPromise)
      pendingTaskInfo += (task.getTaskId -> task)
      taskPromise.future.pipeTo(sender())
    }
    case DeleteTask(taskId) => {
      val taskID = TaskID.newBuilder().setValue(taskId).build()
      val taskPromise = Promise[TaskStatus]()
      taskStatuses.get(taskID) match {
        case Some(taskStatus) =>
          deleteTaskPromises += (taskID -> taskPromise)
          kill(taskID, taskStatus.getAgentId)
        case None =>
          taskPromise.failure(new Exception(s"no task was running with id ${taskId}"))
      }
      taskPromise.future.pipeTo(sender())
    }
    case Teardown => teardown.pipeTo(sender())
    case Reconcile(tasks) => reconcile(tasks)


    //event messages
    case event:Update => handleUpdate(event)
    case event:Offers => handleOffers(event)
    case event:Subscribed =>handleSubscribed(event)
    case event:Event => handleHeartbeat(event)

    case msg => log.warning(s"unknown msg: ${msg}")
  }


  def handleUpdate(event: Update) = {
    log.info(s"received update for ${event.getStatus.getTaskId} in state ${event.getStatus.getState}")

    taskStatuses += (event.getStatus.getTaskId -> event.getStatus)
    pendingTaskPromises.get(event.getStatus.getTaskId) match {
      case Some(promise) => {
        event.getStatus.getState match {
          case TaskState.TASK_RUNNING =>
            promise.success(event.getStatus)
            pendingTaskPromises -= event.getStatus.getTaskId
          case TaskState.TASK_FINISHED | TaskState.TASK_KILLED | TaskState.TASK_KILLING | TaskState.TASK_LOST | TaskState.TASK_ERROR | TaskState.TASK_FAILED =>
            promise.failure(new Exception(s"task in state ${event.getStatus.getState} msg: ${event.getStatus.getMessage}"))
            pendingTaskPromises -= event.getStatus.getTaskId
          case TaskState.TASK_STAGING | TaskState.TASK_STARTING =>
            log.info(s"task still launching task ${event.getStatus.getTaskId} (in state ${event.getStatus.getState}")
        }
      }
      case None => {
      }
    }
    deleteTaskPromises.get(event.getStatus.getTaskId) match {
      case Some(promise) => {
        event.getStatus.getState match {
          case TaskState.TASK_KILLED  =>
            promise.success(event.getStatus)
            deleteTaskPromises -= event.getStatus.getTaskId
          case TaskState.TASK_FINISHED | TaskState.TASK_LOST | TaskState.TASK_ERROR | TaskState.TASK_FAILED =>
            deleteTaskPromises -= event.getStatus.getTaskId
            promise.failure(new Exception(s"task in state ${event.getStatus.getState} msg: ${event.getStatus.getMessage}"))
          case TaskState.TASK_RUNNING | TaskState.TASK_KILLING | TaskState.TASK_STAGING | TaskState.TASK_STARTING =>
            log.info(s"task still killing task ${event.getStatus.getTaskId} (in state ${event.getStatus.getState}")
        }
      }
      case None => {
      }
    }
    acknowledge(event.getStatus)

  }
  def acknowledge(status: TaskStatus): Unit = {
    if (status.hasUuid) {
      val ack = Call.newBuilder()
        .setType(Call.Type.ACKNOWLEDGE)
        .setFrameworkId(frameworkID)
        .setAcknowledge(Call.Acknowledge.newBuilder()
          .setAgentId(status.getAgentId)
          .setTaskId(status.getTaskId)
          .setUuid(status.getUuid)
          .build())
        .build()
      exec(ack).map(resp => {
        if (resp.status.isSuccess()) {
          log.info(s"ack succeeded")
        } else {
          log.warning(s"ack failed! ${resp}")
        }
      })

    }
  }

  import com.google.protobuf.util.JsonFormat

  private def toCompactJsonString(message: com.google.protobuf.Message) =
    JsonFormat.printer.omittingInsignificantWhitespace.print(message)

  def handleOffers(event: Offers) = {

    log.info(s"received ${event.getOffersList.size} offers: ${toCompactJsonString(event);}")


    val matchedTasks = taskMatcher(
      role,
      pendingTaskInfo.values,
      event.getOffersList.asScala.toList)


    //if not tasks matched, we have to explicitly decline all offers

    //if some tasks matched, we explicitly accept the matched offers, and others are implicitly declined
    if (matchedTasks.isEmpty) {
      val offerIds = asScalaBuffer(event.getOffersList).map(offer => offer.getId)

      val declineCall = Call.newBuilder
        .setFrameworkId(frameworkID)
        .setType(Call.Type.DECLINE)
        .setDecline(Call.Decline.newBuilder
          .addAllOfferIds(seqAsJavaList(offerIds)))
        .build;

      exec(declineCall)
        .map(resp => {
          if (resp.status.isSuccess()) {
            log.info(s"decline succeeded")
          } else {
            log.warning("failed!")
          }
        })
    } else {
      val acceptCall = MesosClient.accept(frameworkID,
        matchedTasks.keys.asJava,
        matchedTasks.values.flatten.asJava)

      exec(acceptCall)
        .map(resp => {
          if (resp.status.isSuccess()) {
            log.info(s"accept succeeded")
          } else {
            log.warning(s"accept failed! ${resp}")
          }
        })
      //todo: verify success

      matchedTasks.values.flatten.map(task => {
        pendingTaskInfo -= task.getTaskId
      })

    }




    if (!pendingTaskInfo.isEmpty){
      log.warning("still have pending tasks! (may be oversubscribed)")
    }


  }

  def handleHeartbeat(event: Event) = {
    //TODO: monitor heartbeat
    log.info(s"received heartbeat...")
  }

  def handleSubscribed(event: Subscribed) = {

    //TODO: persist to zk...
    //https://github.com/foursquare/scala-zookeeper-client
    log.info(s"subscribed; frameworkId is ${event.getFrameworkId}")
  }


  val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] = {
    Http().outgoingConnection(host = "localhost", port = 5050)
  }

  def exec(call:Call): Future[HttpResponse] = {
    val req = Post("/api/v1/scheduler")
      .withHeaders(
        RawHeader("Mesos-Stream-Id", streamId))
      .withEntity(MesosClient.protobufContentType, call.toByteArray)


    Source.single(req)
      .via(connectionFlow)
      .runWith(Sink.head)
  }
  def teardown():Future[TeardownComplete.type] = {
    log.info("submitting teardown message...")
    val teardownCall = Call.newBuilder
      .setFrameworkId(frameworkID)
      .setType(Call.Type.TEARDOWN)
      .build;

    //todo: wait for teardown...
    exec(teardownCall)
      .map(resp => {
        if (resp.status.isSuccess()) {
          log.info(s"teardown succeeded")
        } else {
          log.error(s"teardown failed! ${resp}")
        }
        TeardownComplete
      })
  }

  def revive():Unit = {
    log.info("submitting revive message...")
    val reviveCall = Call.newBuilder()
    .setFrameworkId(frameworkID)
    .setType(Call.Type.REVIVE)
    .build()
    exec(reviveCall)
    .map(resp => {
      if (resp.status.isSuccess()) {
        log.info(s"revive succeeded")
      } else {
        log.error(s"revive failed! ${resp}")
      }
    })
  }

  def kill(taskID: TaskID, agentID: AgentID): Unit = {
    val killCall = Call.newBuilder()
      .setFrameworkId(frameworkID)
      .setType(Call.Type.KILL)
      .setKill(Kill.newBuilder()
        .setTaskId(taskID)
        .setAgentId(agentID)
        .build())
    .build()
    exec(killCall)
      .map(resp => {
        if (resp.status.isSuccess()) {
          log.info(s"kill succeeded")
        } else {
          log.error(s"kill failed! ${resp}")
        }
      })
  }

  def shutdown(executorID:ExecutorID, agentID: AgentID): Unit = {
    val shutdownCall = Call.newBuilder()
    .setFrameworkId(frameworkID)
    .setType(Call.Type.SHUTDOWN)
    .setShutdown(Call.Shutdown.newBuilder()
      .setExecutorId(executorID)
      .setAgentId(agentID)
      .build()
    ).build()
    exec(shutdownCall)
  }

  //TODO: implement
  //def message
  //def request

  def reconcile(tasks:Iterable[TaskRecoveryDetail]):Unit = {

    val reconcile = Call.Reconcile.newBuilder()

    tasks.map(task => {
      reconcile.addTasks(Call.Reconcile.Task.newBuilder()
      .setTaskId(TaskID.newBuilder().setValue(task.taskId))
      .setAgentId(AgentID.newBuilder().setValue(task.agentId))  )
    })

    val reconcileCall = Call.newBuilder()
      .setFrameworkId(frameworkID)
    .setType(Call.Type.RECONCILE)
    .setReconcile(reconcile)
    .build()
    exec(reconcileCall)
  }

  def subscribe(mesosClientActor:ActorRef, frameworkID:FrameworkID, frameworkName:String):Unit = {


    import EventStreamUnmarshalling._

    val subscribeCall = Call.newBuilder()
      .setType(Call.Type.SUBSCRIBE)
      .setFrameworkId(frameworkID)
      .setSubscribe(Call.Subscribe.newBuilder
        .setFrameworkInfo(FrameworkInfo.newBuilder
          .setId(frameworkID)
          .setUser(Optional.ofNullable(System.getenv("user")).orElse("root")) // https://issues.apache.org/jira/browse/MESOS-3747
          .setName(frameworkName)
          .setFailoverTimeout(0)
          .setRole(role).build)
        .build())
      .build()


    //TODO: handle connection failures: http://doc.akka.io/docs/akka-http/10.0.5/scala/http/low-level-server-side-api.html
    //see https://gist.github.com/ktoso/4dda7752bf6f4393d1ac
    //see https://tech.zalando.com/blog/about-akka-streams/?gh_src=4n3gxh1
    Http()
      .singleRequest(Post("http://localhost:5050/api/v1/scheduler/subscribe")
        .withHeaders(RawHeader("Accept", "application/x-protobuf"),
          RawHeader("Connection", "close"))
        .withEntity(MesosClient.protobufContentType, subscribeCall.toByteArray))
      .flatMap(response => {
        streamId = response.getHeader("Mesos-Stream-Id").get().value()
        Unmarshal(response).to[Source[Event, NotUsed]]
      })

      .foreach(eventSource => {
        eventSource.runForeach(event => {
          handleEvent(event)
        })
      })
    def handleEvent(event: Event)(implicit ec: ExecutionContext) = {
      event.getType match {
        case Event.Type.OFFERS => mesosClientActor ! event.getOffers
        case Event.Type.HEARTBEAT => mesosClientActor ! event
        case Event.Type.SUBSCRIBED => mesosClientActor ! event.getSubscribed
        case Event.Type.UPDATE => mesosClientActor ! event.getUpdate
        case event => log.warning(s"unhandled event ${event}")
        //todo: handle other event types
      }
    }
  }

}
object MesosClientActor {
  def props(id:String, name:String, master:String, role:String, taskMatcher: (String, Iterable[TaskInfo], Iterable[Offer]) => Map[OfferID,Seq[TaskInfo]] = MesosClient.defaultTaskMatcher): Props =
    Props(new MesosClientActor(id, name, master, role, taskMatcher))
}

object MesosClient {

  val protobufContentType = ContentType(MediaType.applicationBinary("x-protobuf", Compressible, "proto"))


  //TODO: allow task persistence/reconcile

  val defaultTaskMatcher: (String, Iterable[TaskInfo], Iterable[Offer]) => Map[OfferID,Seq[TaskInfo]] =
    (role:String, t: Iterable[TaskInfo], o: Iterable[Offer]) => {
      //we can launch many tasks on a single offer

      var tasksInNeed:ListBuffer[TaskInfo] = t.to[ListBuffer]
      var result = Map[OfferID,Seq[TaskInfo]]()
      o.map(offer => {

        //TODO: manage explicit and default roles, similar to https://github.com/mesos/kafka/pull/103/files


        val agentId = offer.getAgentId
        val resources = offer.getResourcesList.asScala
          .filter(_.getRole == role) //ignore resources with other roles
          .filter(res => Seq("cpus", "mem").contains(res.getName))
          .groupBy(_.getName)
          .mapValues(resources => {
            resources.iterator.next().getScalar.getValue
          })
        if (resources.size == 2) {
          var remainingOfferCpus = resources("cpus")
          var remainingOfferMem = resources("mem")
          var acceptedTasks = ListBuffer[TaskInfo]()
          tasksInNeed.map(task => {

            //TODO: validate task structure (exactly 1 resource each for cpus + mem)
            //agentId should NOT be set (but must be set to some fake value)

            val taskCpus = task.getResourcesList.asScala.filter(_.getName == "cpus").iterator.next().getScalar.getValue
            val taskMem = task.getResourcesList.asScala.filter(_.getName == "mem").iterator.next().getScalar.getValue

            //check for a good fit
            if (remainingOfferCpus > taskCpus &&
              remainingOfferMem > taskMem) {
              remainingOfferCpus -= taskCpus
              remainingOfferMem -= taskMem
              //move the task from InNeed to Accepted
              acceptedTasks += TaskInfo.newBuilder(task).setAgentId(offer.getAgentId).build()
              tasksInNeed -= task
            }
          })
          if (!acceptedTasks.isEmpty){
            result += (offer.getId -> acceptedTasks)
          }
        }
        result
      })
      result
    }








  def accept(frameworkId: FrameworkID, offerIds: java.lang.Iterable[OfferID], tasks: java.lang.Iterable[TaskInfo]): Call =
    Call.newBuilder
      .setFrameworkId(frameworkId)
      .setType(Call.Type.ACCEPT)
      .setAccept(Call.Accept.newBuilder
        .addAllOfferIds(offerIds)
        .addOperations(Offer.Operation.newBuilder
          .setType(Offer.Operation.Type.LAUNCH)
          .setLaunch(Offer.Operation.Launch.newBuilder
            .addAllTaskInfos(tasks)))).build
}

object EventStreamUnmarshalling extends EventStreamUnmarshalling

trait EventStreamUnmarshalling {

  implicit final val fromEventStream: FromEntityUnmarshaller[Source[Event, NotUsed]] = {
    val eventParser = new ServerSentEventParser()

    def unmarshal(entity: HttpEntity) =

      entity.withoutSizeLimit.dataBytes
        .via(RecordIOFraming.scanner())
        .via(eventParser)
        .mapMaterializedValue(_ => NotUsed: NotUsed)

    Unmarshaller.strict(unmarshal).forContentTypes(MesosClient.protobufContentType)
  }
}


private final class ServerSentEventParser() extends GraphStage[FlowShape[ByteString, Event]] {

  override val shape =
    FlowShape(Inlet[ByteString]("ServerSentEventParser.in"), Outlet[Event]("ServerSentEventParser.out"))

  override def createLogic(attributes: Attributes) =
    new GraphStageLogic(shape) with InHandler with OutHandler {

      import shape._

      setHandlers(in, out, this)

      override def onPush() = {
        val line: ByteString = grab(in)
        //unmarshall proto
        val event = Event.parseFrom(line.toArray)
        push(out, event)
      }

      override def onPull() = pull(in)
    }
}