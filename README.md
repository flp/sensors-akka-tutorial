# Mock Traffic Monitoring System
Tutorial for Akka and Akka HTTP.

Start the server and actor system:
```
> sbt run
Server online at http://localhost:6000/
Press RETURN to stop...
```

Simulate sensors using the `sensor.py` script. This will start 3 different sensors:
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
