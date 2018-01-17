name          := "basespace"
organization  := "era7bio"
description   := "basespace project"

bucketSuffix  := "era7.com"

scalaVersion  := "2.11.11"

val playVersion  = "2.5.12"

libraryDependencies ++= Seq(
  "org.scalatest"     %% "scalatest" % "3.0.4" % "test",
  "com.typesafe.play" %% "play-ws"   % playVersion,
)

// For resolving dependencies version conflicts:
dependencyOverrides ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % "1.0.5"
)

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}

// Uncomment if you need to deploy this project as a Statika bundle:
// generateStatikaMetadataIn(Compile)

// Uncomment if you have release-only tests using the assembled fat-jar:
// fullClasspath in assembly := (fullClasspath in Test).value

// Uncomment for Java projects:
// enablePlugin(JavaOnlySettings)
