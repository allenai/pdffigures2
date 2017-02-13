import org.allenai.plugins.CoreDependencies.{allenAiCommon, allenAiTestkit}
import sbtrelease.ReleaseStateTransformations._

name := "pdffigures2"

organization := "org.allenai"

description := "Scala library to extract figures, tables, and captions from scholarly documents"

enablePlugins(LibraryPlugin)

//
// Release settings
//

releaseProcess := Seq(
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  publishArtifacts,
  setNextVersion,
  commitNextVersion,
  pushChanges
)

bintrayPackage := s"${organization.value}:${name.value}_${scalaBinaryVersion.value}"

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))

homepage := Some(url("http://pdffigures2.allenai.org/"))

scmInfo := Some(ScmInfo(
  url("https://github.com/allenai/pdffigures2"),
  "https://github.com/allenai/pdffigures2.git"))

bintrayRepository := "maven"

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

releasePublishArtifactsAction := PgpKeys.publishSigned.value

pomExtra :=
  <developers>
    <developer>
      <id>allenai-dev-role</id>
      <name>Allen Institute for Artificial Intelligence</name>
      <email>dev-role@allenai.org</email>
    </developer>
  </developers>

resolvers ++= Seq(
  "AllenAI Bintray" at "http://dl.bintray.com/allenai/maven",
  "AllenAI Bintray Private" at "http://dl.bintray.com/allenai/private",
  Resolver.jcenterRepo
)

//
// Other settings
//

conflictManager := ConflictManager.default

libraryDependencies ++= Seq(
  "org.allenai.common" %% "common-core" % "1.4.9",
  "org.allenai.common" %% "common-testkit" % "1.4.9",
  "io.spray" %% "spray-json" % "1.3.2",
  "com.github.scopt" %% "scopt" % "3.4.0",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "org.slf4j" % "jcl-over-slf4j" % "1.7.21",
  "org.apache.pdfbox" % "pdfbox" % "2.0.1",
  "org.apache.pdfbox" % "fontbox" % "2.0.1",
  "com.typesafe" % "config" % "1.3.0",

  // So PDFBox can parse more image formats
  "com.github.jai-imageio" % "jai-imageio-core" % "1.2.1",
  "com.github.jai-imageio" % "jai-imageio-jpeg2000" % "1.3.0", // For handling jpeg2000 images

  // So PDFBox can parse security enabled but still readable PDFs
  "org.bouncycastle" % "bcprov-jdk15on" % "1.54",
  "org.bouncycastle" % "bcmail-jdk15on" % "1.54",
  "org.bouncycastle" % "bcpkix-jdk15on" % "1.54"
)

// For scopt
resolvers += Resolver.sonatypeRepo("public")

mainClass in (Compile, run) := Some("org.allenai.pdffigures2.FigureExtractorBatchCli")
mainClass in assembly := Some("org.allenai.pdffigures2.FigureExtractorBatchCli")

assemblyMergeStrategy in assembly := {
  case PathList("org", "apache", "commons", xs @ _*) => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
