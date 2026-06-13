// Migration auf Apache Pekko 2026-06-13. Akka 2.7+ und sbt-akka-grpc 2.2+
// sind BSL und brauchen Lightbend-Maven-Repo-Auth. Pekko ist der Apache 2.0
// Fork (ASF Top-Level-Projekt) mit aktiver Wartung.
addSbtPlugin("org.apache.pekko" % "pekko-grpc-sbt-plugin" % "1.1.1")

addSbtPlugin("com.github.sbt" %% "sbt-native-packager" % "1.9.16")
