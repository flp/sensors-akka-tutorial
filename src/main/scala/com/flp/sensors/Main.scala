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
import play.api.libs.functional.syntax._

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.io.StdIn
import scala.util.Random

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

case class SensorDataMessage(data: JsValue)
case class ParsedDataMessage(data: Map[String, Int])
case class QueryMessage()
case class LocationDataMessage(location: String, data: Map[String, Int])

class QueryActor(locationActors: Seq[ActorRef]) extends Actor {

  private val actorCount = locationActors.size
  private var remainingActors = actorCount
  private var allData: Map[String, Map[String, Int]] = Map()
  private var inquirer: Option[ActorRef] = None

  def receive = {
    case QueryMessage() => {
      println("QueryActor got query")
      locationActors foreach (_ ! QueryMessage())
      inquirer = Some(sender)
    }
    case LocationDataMessage(location, data) => {
      println("QueryActor got location data message")
      allData = allData updated (location, data)
      remainingActors -= 1
      if (remainingActors == 0) {
        println("QueryActor done collecting data, sending to original inquirer")
        val response: Seq[LocationData] = allData.keys.map { loc =>
          LocationData(loc, allData(loc))
        }.toSeq
        inquirer.map (_ ! response)
        // stop ourselves here?
      }
    }
    case _ => println("QueryActor unknown message")
  }
}

class LocationActor extends Actor {

  private var carCount = 0
  private var motorcycleCount = 0
  private var busCount = 0

  def receive = {
    case SensorDataMessage(data) => {
      // Create a DataParserActor and send it the data
      println("LocationActor got json: " + data.toString())
      context.actorOf(Props[DataParserActor]) ! SensorDataMessage(data)
    }
    case ParsedDataMessage(data) =>
      // Update state
      println("LocationActor got parsed data: " + data.toString())
      carCount += data("car")
      motorcycleCount += data("motorcycle")
      busCount += data("bus")
      println(self.path.name + " stats: c = " + carCount + "; m = " + motorcycleCount + "; b = " + busCount)
      // we need to stop the DataParserActor somewhere
    case QueryMessage() =>
      println("cars seen: " + carCount)
      println("motorcycles seen: " + motorcycleCount)
      println("buses seen: " + busCount)
      val data = Map(
        "car" -> carCount,
        "motorcycle" -> motorcycleCount,
        "bus" -> busCount
      )
      sender ! LocationDataMessage(location = self.path.name, data = data)
    case _ => println("LocationActor unknown message")
  }

}

/**
  * DataParserActor should parse json and output an immutable mapping of vehicleType -> count
  */
class DataParserActor extends Actor {
  def receive = {
    case SensorDataMessage(jsData) => {
      println("DataParserActor got json: " + jsData.toString())
      val data: List[String] = (jsData \ "data").as[List[String]]
      var carCount = 0
      var motorcycleCount = 0
      var busCount = 0
      for (d <- data) {
        d match {
          case "car" => carCount += 1
          case "motorcycle" => motorcycleCount += 1
          case "bus" => busCount += 1
          case _ => println("unknown vehicle type")
        }
      }
      val parsedData: Map[String, Int] = Map(
        "car" -> carCount,
        "motorcycle" -> motorcycleCount,
        "bus" -> busCount
      )

      sender ! ParsedDataMessage(parsedData)
      //context.stop(self)
    }
    case _ => println("DataParserActor unknown message")
  }
}

object Main extends App with PlayJsonSupport {

  implicit val sensorEntryReads: Reads[SensorEntry] = Json.reads[SensorEntry]
  implicit val sensorEntryFormat: Format[SensorEntry] = Json.format[SensorEntry] // do we need this
  implicit val locationDataReads = Json.reads[LocationData] // do we need this
  implicit val locationDataFormat = Json.format[LocationData]

  implicit val system = ActorSystem("SensorSystem")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher // do we need this?

  var locationActors: Map[String, ActorRef] = Map()

  val route =
    pathPrefix("sensorapi") {
      path("data") {
        post {
          entity(as[SensorEntry]) { entry =>
            val message = SensorDataMessage(Json.toJson(entry))
            if (locationActors contains entry.location) {
              locationActors(entry.location) ! message
            } else {
              val aRef = system.actorOf(Props[LocationActor], name = entry.location)
              aRef ! message
              locationActors = locationActors updated (entry.location, aRef)
            }
            complete((StatusCodes.Accepted, "sensor data received"))
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
            // get all data. then filter based on query params
            val queryActor = system.actorOf(Props(new QueryActor(locationActors.values.toSeq)))
            implicit val timeout = Timeout(5 seconds)
            // if the casting to Seq[LocationData] fails, we get a error
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

  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)
  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
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

  def getData: JsValue = {
    // get random location
    // generate 10 vehicles, choosing from a weighted list
    val locations = List("west-seattle-bridge", "montlake-cut", "ballard-bridge")
    val weightedVehicleTypes = List("car", "car", "car", "motorcycle", "bus", "bus")

    val location = locations(Random.nextInt(locations.size))
    val vehicles: Seq[String] = (1 to 10).map { _ =>
      weightedVehicleTypes(Random.nextInt(weightedVehicleTypes.size))
    }
    JsObject(Seq(
      "location" -> JsString(location),
      "data" -> JsArray(vehicles.map(JsString(_)))
    ))
  }

  def makeFakeReqs: Unit = {
    val wsb = system.actorOf(Props[LocationActor], name = "west-seattle-bridge")
    val mlc = system.actorOf(Props[LocationActor], name = "montlake-cut")
    val bb = system.actorOf(Props[LocationActor], name = "ballard-bridge")
    val n = 10
    for (_ <- 0 until n) {
      val data = getData
      val location: String = (data \ "location").get.as[String]
      location match {
        case "west-seattle-bridge" => wsb ! SensorDataMessage(data)
        case "montlake-cut" => mlc ! SensorDataMessage(data)
        case "ballard-bridge" => bb ! SensorDataMessage(data)
        case _ => println("unknown location")
      }
    }
  }

}
