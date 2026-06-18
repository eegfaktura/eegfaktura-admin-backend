import Dependencies.*
import com.typesafe.sbt.packager.docker.{Cmd, DockerChmodType, ExecCmd}

ThisBuild / version := "0.2.10-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.9"

val dockerVersion      = "0.2.11"

lazy val root = (project in file("."))
  .enablePlugins(PekkoGrpcPlugin)
  .enablePlugins(JavaAppPackaging)
  .settings(dockerSettings)
  .settings(
    name := "eegfaktura-registration",
    // Pekko ist auf Maven Central, kein Lightbend-Resolver mehr noetig
    libraryDependencies ++= Seq(
        keycloakCore, keycloakAdminClient, keycloakAdapter,
        sttpClient3,
        /*slf4j,*/ scalaLogging, logback,
        akka, akkaStream, akkaHttp, pekkoSlf4j,
        circeCore, circeGeneric, circeParser, akkaHttpCirce,
        slick, slickHikaricp, postgresLib, nimbusdsJwt
    ),

    libraryDependencies ++= Seq(
      scalaTest, streamTestkit, akkaTestkit,
    ).map(_ % Test),

  )

lazy val dockerSettings = Seq(
//  Docker / packageName := "eeg-registration-backend",
//  Docker / maintainer := "vfeeg <vfeeg.org>",
//  Docker / version := appVersion,

  dockerBaseImage := "eclipse-temurin:17-jre",
  dockerRepository := Some("ghcr.io"),
  dockerUsername := Some("vfeeg-development"),
  packageName := "eeg-registration-backend",
  maintainer := "vfeeg <vfeeg.org>",
  dockerUpdateLatest := true,
  dockerExposedVolumes := Seq("/conf"),
  dockerExposedPorts := Seq(8085),
  dockerCommands := dockerCommands.value.filterNot {
    case ExecCmd("ENTRYPOINT", _) => true
    case cmd => false
  },
  dockerCommands ++= Seq(
    //    Cmd("ADD", "application-app.conf", "/conf/application.conf"),
    Cmd("LABEL", s"""version="${dockerVersion}""""),
    ExecCmd("CMD", "/opt/docker/bin/eegfaktura-registration", "-Dconfig.file=/conf/application.conf")
  ),
  dockerChmodType := DockerChmodType.UserGroupWriteExecute,
  dockerAliases ++= {
    val repo = dockerRepository.value
    val name = packageName.value

    Seq(
      DockerAlias(repo, Some("eegfaktura"), name, Some(dockerVersion)),
      DockerAlias(repo, Some("eegfaktura"), name, Some("latest")),
    )
  }

)
