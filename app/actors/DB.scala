package actors

import actors.UserSocket.Message
import actors.UserSocket.Message.messageReads
import actors.ChatMessageWithCreationDate._
import util.SingleLoggingReceive
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe, Unsubscribe}
import akka.event.LoggingReceive
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.twirl.api.HtmlFormat
import play.api.libs.functional.syntax._

import scala.xml.Utility
import scala.concurrent.duration._

import akka.cluster.Cluster

import play.modules.reactivemongo._
import reactivemongo.api.ReadPreference
import play.modules.reactivemongo.json._
import play.modules.reactivemongo.json.collection._
import reactivemongo.bson.{BSONDocument, BSONDateTime }

import extensions.SystemScoped
import akka.actor.{ ActorSystem, Props, ActorRef, Extension, ExtensionId, ExtensionIdProvider, ExtendedActorSystem }
import play.api.Play
import scala.util.{Success, Failure}

object DBService extends SystemScoped {
  override lazy val instanceProps = Props[DBServiceImpl]
  override lazy val instanceName = "db-service-actor"
}

object DBServiceUtil {
  case class Topic(name: String)

  object Topic {
    implicit val topicFormat = Json.format[Topic]
  }
}

class DBServiceImpl extends Actor with ActorLogging {
  import DBServiceUtil._
  import actors.UserSocket._
  import scala.concurrent.ExecutionContext.Implicits.global

  val reactiveMongoApi = Play.current.injector.instanceOf[ReactiveMongoApi]
  val conf = Play.current.injector.instanceOf[play.api.Configuration]

  def coll(name: String) = reactiveMongoApi.database.map(_.collection[JSONCollection](name))

  def buildQuery(topic: String, date: Long, direction: PagerQuery.Direction.Value) : BSONDocument = {
    val op = direction match {
      case PagerQuery.Direction.Older =>
        "$lte"
      case PagerQuery.Direction.Newer =>
        "$gte"
    }
    return BSONDocument(
      "topic" -> topic,
      "creationDate" -> BSONDocument(
        op -> BSONDateTime(date)
        )
      )
  }

  def byDate(direction: PagerQuery.Direction.Value) = {
    val sorting = direction match {
      case PagerQuery.Direction.Older => -1
      case PagerQuery.Direction.Newer => 1
    }
    Json.obj(
      "creationDate" -> sorting
    )
  }

  implicit val messageWriteMode = ChatMessageWithCreationDate.JsonConversionMode.Mongo

  val mediator = DistributedPubSub(context.system).mediator

  val topicsTopic = conf.getString("my.special.string") + "topics"
  val messagesTopic = conf.getString("my.special.string") + "messages"

  context.system.scheduler.scheduleOnce(0 seconds, self, "init")

  def receive = {
    case "init" =>
      reactiveMongoApi.database.onSuccess { case _ =>
        mediator ! Subscribe(topicsTopic, self)
        mediator ! Subscribe(messagesTopic, self)
        context become basic
      }
    case IsDbUp =>
      sender ! DbIsNotUpYet
  }

  def basic = SingleLoggingReceive {
    case IsDbUp =>
      sender ! DbIsUp
    case c : ChatMessageWithCreationDate => 
      for {
        messagesColl <- coll("messages")
        result <- messagesColl.insert(c)
      } {
        // do nothing
      }
    case TopicNameMessage(topicName) => 
      for {
        topicsColl <- coll("topics")
        result <- topicsColl.insert(Topic(topicName))
      } {
        // do nothing
      }
    case GetTopics => 
      val sndr = sender
      val topicsFuture = for {
        topicsColl <- coll("topics")
        topics <- topicsColl.find(Json.obj()).cursor[Topic]().collect[List]()
      } yield topics

      topicsFuture onComplete {
        case Success(topics) if topics.isEmpty =>
          sndr ! NoTopicsFound
        case Success(topics) =>
          sndr ! TopicsListMessage(topics.map(_.name))
        case Failure(err) =>
          sndr ! NoTopicsFound
      }
    case query @ PagerQuery(topic, direction, date) =>
      import PagerQuery.limit
      // we add one to limit because we query messages by date with "≥" ("gte"), not ">" ("gt")
      // we add one second time because that's how we know if there are older messages in db than these
      val limitWithAddition = limit + 2
      val sndr = sender
      val msgsFuture = for {
        messagesColl <- coll("messages")
        messages <- messagesColl
          .find(buildQuery(topic, date, direction))
          .sort(byDate(direction))
          .cursor[ChatMessageWithCreationDate]()
          .collect[Array](limitWithAddition)
        sortedMessages = messages.sorted
      } yield sortedMessages
      
      msgsFuture onComplete {
        case Success(msgs) if msgs.isEmpty =>
          sndr ! NoMessagesFound(query)
        case Success(msgs) =>
          def withoutFarest(ms: Array[ChatMessageWithCreationDate]) = query.direction match {
            case PagerQuery.Direction.Newer => ms.init
            case PagerQuery.Direction.Older => ms.tail
          }
          val isLast = msgs.length < limitWithAddition
          val msgsToSend = if (isLast) msgs else withoutFarest(msgs)
          sndr ! ChatMessagesListMessage(query, isLast, msgsToSend)
        case Failure(_) =>
          sndr ! NoMessagesFound(query)
      }
  }
}
