import com.typesafe.sbt.packager.docker.*
import Dependencies.*

ThisBuild / version := "0.0.3-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.11"

val appVersion      = "0.0.5"

lazy val root = (project in file("."))
  .enablePlugins(AkkaGrpcPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(dockerSettings)
  .settings(
    name := "eegfaktura-registration",

    libraryDependencies ++= Seq(
        keycloakCore, keycloakAdminClient, keycloakAdapter,
        sttpClient3,
        slf4j,
        akka, akkaStream, akkaHttp,
        circeCore, circeGeneric, circeParser, akkaHttpCirce,
        slick, slickHikaricp, postgresLib
    ),

    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.14",
      "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion,
    ).map(_ % Test),

  )

lazy val dockerSettings = Seq(
  Docker / packageName := "eeg-registration-backend",
  Docker / maintainer := "vfeeg <vfeeg.org>",
  Docker / version := appVersion,
  dockerBaseImage := "openjdk:17",
  dockerRepository := Some("ghcr.io"),
  dockerUsername := Some("vfeeg-development"),
  //  dockerUpdateLatest := true,
  dockerExposedVolumes := Seq("/conf"),
  dockerExposedPorts := Seq(8085),
  dockerCommands := dockerCommands.value.filterNot {
    case ExecCmd("ENTRYPOINT", _) => true
    case cmd => false
  },
  dockerCommands ++= Seq(
    //    Cmd("ADD", "application-app.conf", "/conf/application.conf"),
    Cmd("LABEL", s"""version="${appVersion}""""),
    ExecCmd("CMD", "/opt/docker/bin/eegfaktura-registration", "-Dconfig.file=/conf/application.conf")
  ),
  dockerChmodType := DockerChmodType.UserGroupWriteExecute
)
