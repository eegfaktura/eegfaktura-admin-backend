import Dependencies.*
import com.typesafe.sbt.packager.docker.{Cmd, DockerChmodType, ExecCmd}

ThisBuild / version := "0.2.10-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.9"

val dockerVersion      = "0.2.11"

// `latest` (und das Versions-Tag) sind wandernd: die Dev-Zone zieht `latest`. Ein Build aus
// einem preview/**- oder env/**-Branch darf sie deshalb NICHT ueberschreiben, sonst laeuft
// die Dev-Zone unbemerkt auf einem Feature-Stand. Diese Builds pushen nur ihr `sha-<short>`.
val ciRef = sys.env.getOrElse("GITHUB_REF", "")
val isFeatureBranchBuild =
  ciRef.startsWith("refs/heads/preview/") || ciRef.startsWith("refs/heads/env/")

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
        akka, akkaStream, akkaHttp, pekkoSlf4j, pekkoDiscovery,
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
  dockerUpdateLatest := !isFeatureBranchBuild,
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

    // Die uebrigen Repos erzeugen ihre Image-Tags ueber docker/metadata-action und bekommen
    // dadurch immer ein eindeutiges `sha-<short>`. Hier baut sbt-native-packager, das dieses
    // Tag nicht kennt — Preview- und Env-Deploy (ADR-0007/0008) pinnen aber genau darauf und
    // liefen deshalb in ImagePullBackOff. Also selbst erzeugen.
    val shaAlias = sys.env.get("GITHUB_SHA").filter(_.nonEmpty).toSeq.map { sha =>
      DockerAlias(repo, Some("vfeeg-development"), name, Some("sha-" + sha.take(7)))
    }

    val movingAliases =
      if (isFeatureBranchBuild) Seq.empty
      else Seq(
        DockerAlias(repo, Some("vfeeg-development"), name, Some(dockerVersion)),
        DockerAlias(repo, Some("vfeeg-development"), name, Some("latest")),
      )

    movingAliases ++ shaAlias
  }

)
