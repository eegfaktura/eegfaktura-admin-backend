import sbt.*

object Dependencies {

  private val githubPureConfigVersion = "0.17.4"
  private val keycloakVersion = "26.0.6"
  private val keycloakAdapterVersion = "25.0.3"
//  private val keycloakVersion = "24.0.2"
  private val log4CatsVersion = "2.6.0"
  private val mockitoScalaVersion = "1.17.14"
  private val monovoreDeclineVersion = "2.5.0"
  private val awsUtilsVersion = "0.1.92"
  private val sttpClient3Version = "3.8.15"
  private val AkkaVersion = "2.9.3"
  private val AkkaHttpVersion = "10.6.3"
  private val CirceVersion = "0.14.3"
  private val SlickVersion = "3.5.1"


  lazy val authUtils = "uk.gov.nationalarchives" %% "tdr-auth-utils" % "0.0.139"
  lazy val generatedGraphql = "uk.gov.nationalarchives" %% "tdr-generated-graphql" % "0.0.331"
  lazy val s3Utils = "uk.gov.nationalarchives" %% "s3-utils" % awsUtilsVersion
  lazy val stepFunctionUtils = "uk.gov.nationalarchives" %% "stepfunction-utils" % awsUtilsVersion
  lazy val bagit = "gov.loc" % "bagit" % "5.2.0"
  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "3.5.0"
  lazy val decline = "com.monovore" %% "decline" % monovoreDeclineVersion
  lazy val declineEffect = "com.monovore" %% "decline-effect" % monovoreDeclineVersion
  lazy val graphqlClient = "uk.gov.nationalarchives" %% "tdr-graphql-client" % "0.0.240"
  lazy val scalaCsv = "com.github.tototoshi" %% "scala-csv" % "1.3.10"
  lazy val log4cats = "org.typelevel" %% "log4cats-core" % log4CatsVersion
  lazy val log4catsSlf4j = "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion
  lazy val pureConfig = "com.github.pureconfig" %% "pureconfig" % githubPureConfigVersion
  lazy val pureConfigCatsEffect = "com.github.pureconfig" %% "pureconfig-cats-effect" % githubPureConfigVersion
  lazy val slf4j = "org.slf4j" % "slf4j-simple" % "2.0.17"
  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
  lazy val logback = "ch.qos.logback" % "logback-classic" % "1.5.18"
  lazy val mockitoScala = "org.mockito" %% "mockito-scala" % mockitoScalaVersion
  lazy val mockitoScalaTest = "org.mockito" %% "mockito-scala-scalatest" % mockitoScalaVersion
  lazy val keycloakCore = "org.keycloak" % "keycloak-core" % keycloakVersion exclude("com.fasterxml.jackson.core", "jackson-databind")
  lazy val keycloakAdminClient = "org.keycloak" % "keycloak-admin-client" % keycloakVersion
  lazy val keycloakAdapter = "org.keycloak" % "keycloak-adapter-core" % keycloakAdapterVersion exclude("com.fasterxml.jackson.core", "jackson-databind")
  lazy val sttpClient3 = "com.softwaremill.sttp.client3" %% "core" % sttpClient3Version
  lazy val akka = "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion
  lazy val akkaStream = "com.typesafe.akka" %% "akka-stream" % AkkaVersion
  lazy val akkaHttp = "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion
  lazy val akkaHttpCirce = "de.heikoseeberger" %% "akka-http-circe" % "1.37.0"
  lazy val circeCore = "io.circe" %% "circe-core" % CirceVersion
  lazy val circeGeneric = "io.circe" %% "circe-generic" % CirceVersion
  lazy val circeParser =  "io.circe" %% "circe-parser" % CirceVersion
  lazy val slick = "com.typesafe.slick" %% "slick" % SlickVersion
  lazy val slickHikaricp = "com.typesafe.slick" %% "slick-hikaricp" % SlickVersion
  lazy val postgresLib = "org.postgresql" % "postgresql" % "42.2.5"
  lazy val nimbusdsJwt = "com.nimbusds" % "nimbus-jose-jwt" % "9.31"
//  "org.postgresql" % "postgresql" % "42.2.5"

  lazy val slickTestkit = "com.typesafe.slick" %% "slick-testkit" % SlickVersion
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.16"
  lazy val streamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion
  lazy val akkaTestkit = "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion
}
