name          := "basespace"
organization  := "era7bio"
description   := "basespace project"

bucketSuffix  := "era7.com"

val playVersion  = "1.1.2"

libraryDependencies ++= Seq(
  // Play
  "com.typesafe.play" %% "play-ahc-ws-standalone"  % playVersion,
  "com.typesafe.play" %% "play-ws-standalone-json" % playVersion
)

// For resolving dependencies version conflicts:
dependencyOverrides ++= Seq(
  // Conflicts from Play and JSON
  "com.typesafe" % "config"    % "1.3.1",
  "joda-time"    % "joda-time" % "2.9.9"
)

// Uncomment if you need to deploy this project as a Statika bundle:
// generateStatikaMetadataIn(Compile)

// Uncomment if you have release-only tests using the assembled fat-jar:
// fullClasspath in assembly := (fullClasspath in Test).value

// Uncomment for Java projects:
// enablePlugin(JavaOnlySettings)
