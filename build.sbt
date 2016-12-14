name := "sensors"

version := "1.0"

scalaVersion := "2.11.8"

resolvers ++= Seq(
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  Resolver.bintrayRepo("hseeberger", "maven")
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.14",
  "com.typesafe.akka" %% "akka-http" % "10.0.0",
  "com.typesafe.play" %% "play-json" % "2.5.8",
  "de.heikoseeberger" %% "akka-http-play-json" % "1.10.1"
)
    