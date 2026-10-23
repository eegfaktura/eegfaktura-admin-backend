import sbt.*

object Dependencies {

  private val githubPureConfigVersion = "0.17.4"
  private val keycloakVersion = "26.0.6"
//  private val keycloakVersion = "24.0.2"
  private val log4CatsVersion = "2.6.0"
  private val mockitoScalaVersion = "1.17.14"
  private val monovoreDeclineVersion = "2.5.0"
  private val awsUtilsVersion = "0.1.92"
  private val sttpClient3Version = "3.8.15"
  // Migration auf Apache Pekko 2026-06-13 (statt BSL-lizenziertem Akka).
  // Pekko 1.0.x = Apache-Fork von Akka 2.6.x. Pekko 1.2.x folgt Akka 2.7-API.
  // 1.2.1 ist die aelteste auf Maven Central verfuegbare 1.x-Version fuer
  // pekko-actor-typed. pekko-http 1.2.0 + pekko-grpc-sbt-plugin 1.1.1 sind
  // mit pekko 1.2.x kompatibel (semver).
  private val PekkoVersion = "1.2.1"
  private val PekkoHttpVersion = "1.2.0"
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
  lazy val sttpClient3 = "com.softwaremill.sttp.client3" %% "core" % sttpClient3Version
  lazy val akka = "org.apache.pekko" %% "pekko-actor-typed" % PekkoVersion
  lazy val akkaStream = "org.apache.pekko" %% "pekko-stream" % PekkoVersion
  lazy val akkaHttp = "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion
  lazy val akkaHttpCirce = "com.github.pjfanning" %% "pekko-http-circe" % "3.1.0"
  // pekko-slf4j wird via application.conf als logger geladen, deshalb explizit
  lazy val pekkoSlf4j = "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion
  // pekko-discovery explizit auf PekkoVersion pinnen: pekko-grpc-sbt-plugin
  // 1.1.1 zieht transitiv pekko-discovery 1.1.2, was mit pekko 1.2.x als
  // Mixed-Versioning zur Laufzeit crasht (IllegalStateException beim Start).
  lazy val pekkoDiscovery = "org.apache.pekko" %% "pekko-discovery" % PekkoVersion
  lazy val circeCore = "io.circe" %% "circe-core" % CirceVersion
  lazy val circeGeneric = "io.circe" %% "circe-generic" % CirceVersion
  lazy val circeParser =  "io.circe" %% "circe-parser" % CirceVersion
  lazy val slick = "com.typesafe.slick" %% "slick" % SlickVersion
  lazy val slickHikaricp = "com.typesafe.slick" %% "slick-hikaricp" % SlickVersion
  lazy val postgresLib = "org.postgresql" % "postgresql" % "42.7.13"
  lazy val nimbusdsJwt = "com.nimbusds" % "nimbus-jose-jwt" % "9.37.4"

  // Pins for transitive dependencies with known HIGH CVEs (platform#183). They are declared as
  // dependencies (like pekkoDiscovery), whole families included, so that the exported pom, which the
  // security scan reads, resolves the same versions as the runtime; dependencyOverrides alone do not
  // reach the pom.
  private val JacksonVersion = "2.18.12"
  lazy val jacksonCore = "com.fasterxml.jackson.core" % "jackson-core" % JacksonVersion
  lazy val jacksonFamily = Seq(
    jacksonCore,
    "com.fasterxml.jackson.core" % "jackson-databind" % JacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-annotations" % JacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % JacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % JacksonVersion,
    // used by keycloak-admin-client (RESTEasy) next to databind
    "com.fasterxml.jackson.jakarta.rs" % "jackson-jakarta-rs-base" % JacksonVersion,
    "com.fasterxml.jackson.jakarta.rs" % "jackson-jakarta-rs-json-provider" % JacksonVersion,
    "com.fasterxml.jackson.module" % "jackson-module-jakarta-xmlbind-annotations" % JacksonVersion,
  )
  // Same version as pekko-grpc 1.2.0 uses; the sbt plugin stays on 1.1.1 (1.2.0 needs sbt 1.9+,
  // this build is on 1.8.3), so only the runtime libraries move, the generated code does not change.
  private val GrpcVersion = "1.75.0"
  lazy val grpcNettyShaded = "io.grpc" % "grpc-netty-shaded" % GrpcVersion
  lazy val grpcFamily = Seq(grpcNettyShaded) ++
    Seq("grpc-api", "grpc-context", "grpc-core", "grpc-protobuf", "grpc-protobuf-lite", "grpc-stub", "grpc-util")
      .map("io.grpc" % _ % GrpcVersion)
  // scalapb-runtime 0.11.x declares protobuf-java 3.19.6; at runtime grpc-protobuf already brings 3.25.x.
  // Only the runtime library: the code generator (protoc in the sbt plugin) is not affected.
  lazy val protobufJava = "com.google.protobuf" % "protobuf-java" % "3.25.8"
  // via circe-parser (pom: 1.4.0, runtime: 1.6.0 through pekko-http-circe's circe 0.14.12)
  lazy val jawnParser = "org.typelevel" %% "jawn-parser" % "1.7.0"
  // via keycloak-admin-client -> resteasy-multipart-provider -> apache-mime4j-storage; the RESTEasy BOM
  // sets 2.11.0 (CVE-2024-47554, fixed in 2.14.0) (platform#190)
  lazy val commonsIo = "commons-io" % "commons-io" % "2.20.0"
//  "org.postgresql" % "postgresql" % "42.2.5"

  lazy val slickTestkit = "com.typesafe.slick" %% "slick-testkit" % SlickVersion
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.16"
  lazy val streamTestkit = "org.apache.pekko" %% "pekko-stream-testkit" % PekkoVersion
  lazy val akkaTestkit = "org.apache.pekko" %% "pekko-actor-testkit-typed" % PekkoVersion
}
