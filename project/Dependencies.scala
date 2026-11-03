import sbt.*

object Dependencies {

  private val keycloakVersion = "26.0.6"
  // Migration auf Apache Pekko 2026-06-13 (statt BSL-lizenziertem Akka).
  // Pekko 1.0.x = Apache-Fork von Akka 2.6.x. Pekko 1.2.x folgt Akka 2.7-API.
  // 1.2.1 ist die aelteste auf Maven Central verfuegbare 1.x-Version fuer
  // pekko-actor-typed. pekko-http 1.2.0 + pekko-grpc-sbt-plugin 1.1.1 sind
  // mit pekko 1.2.x kompatibel (semver).
  private val PekkoVersion = "1.2.1"
  private val PekkoHttpVersion = "1.2.0"
  private val CirceVersion = "0.14.3"
  private val SlickVersion = "3.5.1"

  lazy val logback = "ch.qos.logback" % "logback-classic" % "1.5.18"
  // same version as the slf4j-api that logback brings; java.util.logging (gRPC, pgjdbc) through SLF4J
  lazy val julToSlf4j = "org.slf4j" % "jul-to-slf4j" % "2.0.17"
  lazy val keycloakCore = "org.keycloak" % "keycloak-core" % keycloakVersion exclude("com.fasterxml.jackson.core", "jackson-databind")
  lazy val keycloakAdminClient = "org.keycloak" % "keycloak-admin-client" % keycloakVersion
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
  // comes in through gRPC; 33.7.2 fixes CVE-2026-102554
  lazy val guava = "com.google.guava" % "guava" % "33.7.2-android"

  // Same version as pekko-grpc 1.2.0 uses; the sbt plugin stays on 1.1.1 (a plugin update is its own
  // change), so only the runtime libraries move, the generated code does not change.
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
  // sets 2.11.0 (CVE-2024-47554, fixed in 2.14.0) (platform#190). Remove when the RESTEasy BOM of
  // keycloak-admin-client manages commons-io >= 2.14.0.
  lazy val commonsIo = "commons-io" % "commons-io" % "2.20.0"

  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.16"
  lazy val streamTestkit = "org.apache.pekko" %% "pekko-stream-testkit" % PekkoVersion
  lazy val akkaTestkit = "org.apache.pekko" %% "pekko-actor-testkit-typed" % PekkoVersion
}
