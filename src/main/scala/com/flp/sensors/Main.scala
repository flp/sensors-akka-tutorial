package com.flp.sensors

import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.io.StdIn

/**
  * Created by Franklin on 12/12/16.
  */

/**
  * Example sensor data
  {
    "location": "west-seattle-bridge",
    "data": ["car", "motorcycle", "car", "car", "bus"]
  }
  */
case class SensorEntry(location: String, data: Seq[String])
case class LocationData(location: String, data: Map[String, Int])

case class SensorDataMessage(data: Seq[String])
case class ParsedDataMessage(data: Map[String, Int])
case class QueryMessage()
case class LocationDataMessage(location: String, data: Map[String, Int])

class QueryActor(locationActors: Seq[ActorRef]) extends Actor {

  private val actorCount = locationActors.size
  private var remainingActors = actorCount
  private var allData: Map[String, Map[String, Int]] = Map()
  private var inquirer: Option[ActorRef] = None

  def receive = {
    case QueryMessage() =>
      locationActors foreach (_ ! QueryMessage())
      inquirer = Some(sender)
    case LocationDataMessage(location, data) =>
      allData = allData updated (location, data)
      remainingActors -= 1
      if (remainingActors == 0) {
        val response: Seq[LocationData] = allData.keys.map { loc =>
          LocationData(loc, allData(loc))
        }.toSeq
        inquirer.map (_ ! response)
        context.stop(self)
      }
    case _ => println("QueryActor unknown message")
  }
}

class LocationActor(location: String) extends Actor {

  private var carCount = 0
  private var motorcycleCount = 0
  private var busCount = 0

  def receive = {
    case msg: SensorDataMessage =>
      context.actorOf(Props[DataParserActor]) ! msg
    case ParsedDataMessage(data) =>
      carCount += data("car")
      motorcycleCount += data("motorcycle")
      busCount += data("bus")
    case QueryMessage() =>
      val data = Map(
        "car" -> carCount,
        "motorcycle" -> motorcycleCount,
        "bus" -> busCount
      )
      sender ! LocationDataMessage(location = location, data = data)
    case _ => println("LocationActor got unknown message")
  }
}

class DataParserActor extends Actor {
  def receive = {
    case SensorDataMessage(data) =>
      val parsedData = Map(
        "car" -> data.count(_ == "car"),
        "motorcycle" -> data.count(_ == "motorcycle"),
        "bus" -> data.count(_ == "bus")
      )
      sender ! ParsedDataMessage(parsedData)
      context.stop(self)
    case _ => println("DataParserActor got unknown message")
  }
}

object Main extends App with PlayJsonSupport {

  implicit val sensorEntryReads: Reads[SensorEntry] = Json.reads[SensorEntry]
  implicit val locationDataFormat = Json.format[LocationData]

  implicit val system = ActorSystem("SensorSystem")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  implicit val timeout = Timeout(5 seconds)

  var locationActors: Map[String, ActorRef] = Map()

  val routes =
    pathPrefix("sensorapi") {
      path("data") {
        post {
          entity(as[SensorEntry]) { entry =>
            if (locationActors contains entry.location) {
              locationActors(entry.location) ! SensorDataMessage(entry.data)
            } else {
              val aRef = system.actorOf(Props(new LocationActor(entry.location)))
              aRef ! SensorDataMessage(entry.data)
              locationActors = locationActors updated (entry.location, aRef)
            }
            complete(StatusCodes.Accepted)
          }
        }
      }
    } ~
    pathPrefix("api") {
      path("locations") {
        get {
          val locations = Json.stringify(Json.toJson(locationActors.keys))
          complete(HttpEntity(ContentTypes.`application/json`, locations))
        }
      } ~
      path("data") {
        get {
          parameterMap { m =>
            val queryActor = system.actorOf(Props(new QueryActor(locationActors.values.toSeq)))
            val data: Future[Seq[LocationData]] = (queryActor ? QueryMessage()).mapTo[Seq[LocationData]]

            val filteredData: Future[Seq[LocationData]] = data map { locs =>
              var response: Seq[LocationData] = locs
              if (m contains "location") response = filterForLocation(response, m("location"))
              if (m contains "vehicle") response = filterForVehicle(response, m("vehicle"))
              response
            }

            complete(filteredData)
          }
        }
      }
    }

  val bindingFuture = Http().bindAndHandle(routes, "localhost", 6000)
  println(s"Server online at http://localhost:6000/\nPress RETURN to stop...")
  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())

  def filterForLocation(locs: Seq[LocationData], location: String): Seq[LocationData] = {
    locs.filter(_.location == location)
  }

  def filterForVehicle(locs: Seq[LocationData], vehicle: String): Seq[LocationData] = {
    locs map { locData =>
      val filteredVehicleData = locData.data.filter(p => p._1 == vehicle)
      LocationData(locData.location, filteredVehicleData)
    }
  }
}
