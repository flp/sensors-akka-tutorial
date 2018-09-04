# Mock Traffic Monitoring System
Tutorial for Akka and Akka HTTP.

## Blog post
<details><summary>Contents</summary>

# Getting Started with Akka and Akka HTTP: Building a Mock Traffic Monitoring System

---

# What is Akka?
Akka is a library that makes it easy to write concurrent and distributed applications atop the JVM (Java Virtual Machine). Why is such a library valuable? Nowadays computers often contain multiprocessors containing two or more cores. With the scale of today's applications it is feasible that your code will be running on multiple machines, and being able to communicate between nodes is essential. Writing code that can take advantage of such a distributed processing environment is a very difficult problem, prone to tricky, hard-to-drack-down bugs. Akka takes this problem and makes it much easier to manage, primarily through the use of actors.

This tutorial will explain the basics of Akka via a small project, written in Scala, which models the backend of a traffic monitoring system. Imagine that we have multiple sensors spread across a city, all simultaneously collecting data and sending it to our server(s). A sensor detects whenever a vehicle goes through a given checkpoint and logs the vehicle's type (car, motorcycle, or bus). Sensors send up logged data in batches. We want to store the data, aggregate it, and respond to queries about the data. In addition to Akka we will also use a simple Python script for sending fake sensor data and an Akka HTTP web server for accepting sensor data and responding to queries on the data.

# Actors
Nearly all of the behavior for our traffic monitoring project will be encapsulated in actors. Actors contain state and behavior and communicate with one another via message passing. Actors are not unique to Akka. The [actor model](https://en.wikipedia.org/wiki/Actor_model) is a concurrent computation model dating back to 1973. In Akka, actors are the basic building blocks of a system. When an actor receives a message, it decodes the message and takes some action. It can perform some computation, update its own state, send messages to other actors, and/or create more actors. Each actor typically has a well-defined role. If a single actor's responsibilities become too much, it is good practice to have the actor create more actors to split up the work.

## DataParserActor
Our first actor is very simple. A `DataParserActor`'s job is to make sense of a single piece of raw data from a sensor and send the results back.

To implement an actor you extend the Akka Actor trait and implement its `receive` method. Inside the `receive` method you handle different types of messages via pattern matching.
```scala line-numbers
case class SensorDataMessage(data: Seq[String])
case class ParsedDataMessage(data: Map[String, Int])

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
```
`DataParserActor` only handles a single message type: `SensorDataMessage`. A `SensorDataMessage` contains an unordered sequence of all the vehicle types detected in the last sensing interval.

When we receive a `SensorDataMessage`, we read the data to get counts for the different vehicle types. Then we send a `ParsedDataMessage` containing the vehicle counts back to the `sender`. Then, having fulfilled our duty, we terminate the `DataParserActor`.

* `sender` is a reference (an `ActorRef`, specifically) to the sender of the current message
* `!` ("tell") sends a one-way asynchronous message
* `context` exposes contextual information and methods for the actor and the current message
* `self` is the `ActorRef` for the actor itself

## LocationActor
Our next actor is `LocationActor`, which aggregates all of the vehicle data for a given location.
```scala line-numbers
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
    case _ => println("LocationActor got unknown message")
  }
}
```
A `LocationActor` handles two types of messages: `SensorDataMessage` and `ParsedDataMessage`. 

When a `SensorDataMessage` comes in, we create a `DataParserActor` and forward the message to it. Actor creation happens with `context.actorOf()`. This creates a new actor which is a direct child of the current actor. When a `ParsedDataMessage` comes in, we read the vehicle counts from the data in the message and update the state variables for the `LocationActor`.

To sum up what we have done so far: we have created a hierarchical structure, containing just two actors so far, for handling sensor data. `LocationActors` receive `SensorDataMessages` and create `DataParserActors` to handle the messages. A child `DataParserActor` parses sensor data and send the results back to its parent. Finally, the parent `LocationActor` saves the results in its private state variables.

Next, we will build the web server and learn how to interact with our actor system from the outside.

# Akka HTTP Web Server
Akka HTTP is a set of modules, built on Akka, designed to support the full HTTP stack. It is not a full web framework but rather a lightweight toolkit for creating and working with HTTP services. We will use it to create a web server with a simple REST API. In just a few lines of code we can get a web server running:
```scala line-numbers
object Main extends App {

  implicit val system = ActorSystem("SensorSystem")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val routes = ... // no routes definition yet

  val bindingFuture = Http().bindAndHandle(routes, "localhost", 6000)
  println(s"Server online at http://localhost:6000/\nPress RETURN to stop...")
  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())
}
```
Let's dive into the individual lines of code:

* `implicit val system = ActorSystem("SensorSystem")` - Akka HTTP depends on Akka actors and thus requires an `ActorSystem` to be in scope. This same `ActorSystem` is the basis of our traffic monitoring backend.
* `implicit val materializer = ActorMaterializer()` - Akka HTTP also depends on Akka streams, and an `ActorMaterializer` is necessary for setting up the relevant infrastructure.
* `implicit val executionContext = system.dispatcher` - We need this for handling the responses we get from querying our actors via Scala's `Future` construct.
* `val bindingFuture = Http().bindAndHandle(routes, "localhost", 6000)` - creates an HTTP server running on localhost on port 6000 with the specified routes handler.
* `bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate())` - starts the HTTP server and tells the server to terminate the `ActorSystem` when the server finishes.

And now, at a high level, the routes for our web server:

* `POST /sensorapi/data` - upload sensor data. This is the endpoint that the sensors will use to send data to the server.
* `GET /api/locations` - get a list of all the locations our server has seen, as JSON.
* `GET /api/data` - query the sensor data and get results back as JSON. This endpoint accepts two query params, `location` and `vehicle`. `location` filters the data to match a specific location. `vehicle` filters the data to match a specific vehicle type.

## POST - Upload Sensor Data
We will create a route that accepts POST requests containing JSON. Our sensors will always send JSON in same format: two keys, "location" and "data", where the location value is a string and the data value is all the vehicle types detected in the last sensing interval as an unordered list of strings. For example:
```
{
    "location": "west-seattle-bridge",
    "data": ["car", "motorcycle", "car", "car", "bus"]
}
``` 
We need to deserialize the JSON coming from the data sensors into data objects that we can use. We will use a `case class` and the [JSON library from the Play Framework](https://www.playframework.com/documentation/2.5.x/api/scala/index.html#play.api.libs.json.package).

```scala line-numbers
import play.api.libs.json._

case class SensorEntry(location: String, data: Seq[String])
implicit val sensorEntryReads: Reads[SensorEntry] = Json.reads[SensorEntry]
```
`sensorEntryReads` will provide a conversion from a `JsValue` (Play's generic JSON data type) into a `SensorEntry` object. In order to convert the body of an HTTP POST request into a `JsValue` (in Akka HTTP this process is known as unmarshalling) we will use the Play JSON module of [akka-http-json](https://github.com/hseeberger/akka-http-json). We just need to mix the `PlayJsonSupport` trait into our `Main` object:
```scala line-numbers
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport

object Main extends App with PlayJsonSupport { ... }
```
`PlayJsonSupport` provides an implicit conversion from an `HttpEntity` to a `JsValue`. Now we can convert an `HttpEntity` into a `SensorEntry` via the implicit unmarshaller from `PlayJsonSupport` and the implicit `sensorEntryReads`. `PlayJsonSupport` also provides support for marshalling a `JsValue` into the body of an HTTP response, which we will use for GET requests.

The basic workflow for handling a POST request will be:

* Check to see if we already have a `LocationActor` for this location. If we do, send a `SensorDataMessage` containing the vehicle data to the `LocationActor`.
* Otherwise, create a new `LocationActor` and send it a `SensorDataMessage`. Save the new `LocationActor`.
* Finish by responding with a 202 (Accepted) HTTP status code with an empty body.

```scala line-numbers
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
  }
```
Routes in Akka HTTP are constructed using `Directives` that narrow down requests to match a specific path and HTTP method. Here we use `pathPrefix` and `path`, two directives, to match requests with the `/sensorapi/data` path. Then we narrow it down to only handle POST requests using `post`, another directive. `entity(as[SensorEntry])` performs the unmarshalling we talked about above, taking the `HttpEntity` and outputting a `SensorEntry` object.

We are using a map to store and lookup the `ActorRefs` for our `LocationActors`. Notice that we use `system.actorOf()` instead of `context.actorOf()` to create new actors because we are not operating inside of an actor context here. The `Props` syntax is different here as well, because the constructor of `LocationActor` takes a parameter.

## GET - Sensor Locations
```scala line-numbers
pathPrefix("api") {
  path("locations") {
    get {
      val locations = Json.stringify(Json.toJson(locationActors.keys))
      complete(HttpEntity(ContentTypes.`application/json`, locations))
    }
  } ~
  ... // implementation for GET /api/data will follow 
}
```
To get all locations we use the keys of the `locationActors` map. Then we convert the locations into JSON string format with Play's `Json.toJson` and `Json.stringify`. We complete the request by responding with the JSON.

`~`, seen here at the end of the route, is an Akka HTTP method for chaining routes together.

## GET - Traffic Data
In order to respond to requests for data about the entire sensor system we need a way to aggregate data across multiple `LocationActors`. We will use another actor, `QueryActor`, to do this.
```scala line-numbers
case class LocationData(location: String, data: Map[String, Int])

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
```
`LocationData` is a data model object we will use to construct the response to the GET request. `QueryMessage` and `LocationDataMessage` are two new messages we will use to coordinate between the actors.

A `QueryActor` handles messages of type `QueryMessage` and `LocationDataMessage`:

* A `QueryMessage` means we should collect the data for all of the `LocationActors` we know of. To do so we send a `QueryMessage` to each `LocationActor`, asking for data. We also set the `inquirer` variable, so that we know where to send the aggregated result.
* A `LocationDataMessage` contains the data from a single `LocationActor`. For each `LocationDataMessage` we update a state variable inside `QueryActor` with the new data. A single `QueryActor` handles just one query. So then we can just keep track of how many `LocationDataMessages` we receive, and once we receive a message from each `LocationActor`, we send the aggregated data back to the `inquirer` and shut ourselves down.

We also need to update `LocationActor` to be able to handle `QueryMessage`:
```scala line-numbers
class LocationActor(location: String) extends Actor {
  ...
  def receive = {
    ...
    case QueryMessage() =>
      val data = Map(
        "car" -> carCount,
        "motorcycle" -> motorcycleCount,
        "bus" -> busCount
      )
      sender ! LocationDataMessage(location = location, data = data)
    ...
  }
}
```
Now we can implement the `GET /api/data` route on the HTTP server:
```scala line-numbers
pathPrefix("api") {
  path("locations") { ... } ~
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
```
There are quite a few things going on here:

* We get the query parameters with `parameterMap`, which extracts the request's query parameters into a `Map[String, String]`.
* Then we create a `QueryActor`, passing all of the `LocationActor` `ActorRefs` into the constructor.
* We send a message to the `QueryActor` using `?` (“ask”), which returns a `Future`, and map that `Future` to a `Seq[LocationData]`. A `Future` represents a value that doesn't exist yet. `Futures` allow us to handle values resulting from asynchronous computations.
* We filter the data based on the query params. `filterForLocation` and `filterForVehicle` are custom functions that handle the filtering.
* Finally we complete the request with the filtered data.

And with that, our traffic monitoring system is done. You can see all of the code together in this [gist](https://gist.github.com/flp/61448165121d9c7065276ca5774afdee).

# Testing it out
We can use `sbt` to run the code.
```
> sbt run
Server online at http://localhost:6000/
Press RETURN to stop...
```

We will use a Python script to simulate multiple sensors.
```
> python sensor.py west-seattle-bridge 6 1 2 40 1 &
Sensor started at west-seattle-bridge with weights 6:1:2 (c:m:b). 40 vehicles every 1 seconds.
Sending data to http://localhost:6000/sensorapi/data

> python sensor.py ballard-bridge 2 1 1 20 1 &
Sensor started at ballard-bridge with weights 2:1:1 (c:m:b). 20 vehicles every 1 seconds.
Sending data to http://localhost:6000/sensorapi/data

> python sensor.py montlake-cut 2 0 1 35 1 &
Sensor started at montlake-cut with weights 2:0:1 (c:m:b). 35 vehicles every 1 seconds.
Sending data to http://localhost:6000/sensorapi/data
```
The command-line args, in left-to-right order, are location, car weight, motorcycle weight, bus weight, vehicles per sensing period, and period of sending sensor data, in seconds. The full project code containing the Python script and build.sbt file is published in this [GitHub repository](https://github.com/flp/sensors-akka-tutorial).

And now we can curl some GET requests to verify that the system is working.
```
> curl localhost:6000/api/locations
["west-seattle-bridge","ballard-bridge","montlake-cut"]

> curl localhost:6000/api/data
[ {
  "location" : "west-seattle-bridge",
  "data" : {
    "car" : 3260,
    "motorcycle" : 534,
    "bus" : 1086
  }
}, {
  "location" : "montlake-cut",
  "data" : {
    "car" : 1747,
    "motorcycle" : 0,
    "bus" : 913
  }
}, {
  "location" : "ballard-bridge",
  "data" : {
    "car" : 1034,
    "motorcycle" : 499,
    "bus" : 447
  }
} ]

> curl "localhost:6000/api/data?location=west-seattle-bridge&vehicle=car"
[ {
  "location" : "west-seattle-bridge",
  "data" : {
    "car" : 4235
  }
} ]
```
Notice that the number of cars detected at "west-seattle-bridge" is different between the two queries to `/api/data`. This is because in the time it took me to enter the second command the sensing script for "west-seattle-bridge" had already sent more data.

---
# Conclusion
We now know how easy it is to write concurrent applications using Akka and Scala. And, with Akka HTTP, it is possible to create a fully functional HTTP web server in just a few lines of code, without any extra configuration files. I hope this tutorial has motivated you to learn more about Akka, as we have only scratched the surface here. Check out the [Akka website](http://akka.io/) to find out more.
</details>

## Start the server and actor system:
```
> sbt run
Server online at http://localhost:6000/
Press RETURN to stop...
```

Simulate sensors using the `sensor.py` script. This will start 3 different sensors in the background:
```
> python sensor.py west-seattle-bridge 6 1 2 40 1 &
Sensor started at west-seattle-bridge with weights 6:1:2 (c:m:b). 40 vehicles every 1 seconds.
Sending data to http://localhost:6000/sensorapi/data

> python sensor.py ballard-bridge 2 1 1 20 1 &
Sensor started at ballard-bridge with weights 2:1:1 (c:m:b). 20 vehicles every 1 seconds.
Sending data to http://localhost:6000/sensorapi/data

> python sensor.py montlake-cut 2 0 1 35 1 &
Sensor started at montlake-cut with weights 2:0:1 (c:m:b). 35 vehicles every 1 seconds.
Sending data to http://localhost:6000/sensorapi/data
```

Use `curl` to send GET requests and see aggregated data from the actor system:
```
> curl localhost:6000/api/locations
["west-seattle-bridge","ballard-bridge","montlake-cut"]

> curl localhost:6000/api/data
[ {
  "location" : "west-seattle-bridge",
  "data" : {
    "car" : 3260,
    "motorcycle" : 534,
    "bus" : 1086
  }
}, {
  "location" : "montlake-cut",
  "data" : {
    "car" : 1747,
    "motorcycle" : 0,
    "bus" : 913
  }
}, {
  "location" : "ballard-bridge",
  "data" : {
    "car" : 1034,
    "motorcycle" : 499,
    "bus" : 447
  }
} ]

> curl "localhost:6000/api/data?location=west-seattle-bridge&vehicle=car"
[ {
  "location" : "west-seattle-bridge",
  "data" : {
    "car" : 4235
  }
} ]
```
