lazy val scala211 = "2.11.12"
lazy val scala212 = "2.12.9"
lazy val scala213 = "2.13.0" // Not supported yet (collections changes required in common)
lazy val supportedScalaVersions = List(scala212, scala211)

ThisBuild / organization := "org.allenai"
ThisBuild / description  := "Scala library to extract figures, tables, and captions from scholarly documents"
ThisBuild / scalaVersion := scala212
ThisBuild / version      := "0.1.0"

lazy val projectSettings = Seq(
  name := "pdffigures2",
  crossScalaVersions := supportedScalaVersions,
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("http://pdffigures2.allenai.org/")),
  scmInfo := Some(ScmInfo(
    url("https://github.com/allenai/pdffigures2"),
    "https://github.com/allenai/pdffigures2.git")),
  bintrayPackage := s"${organization.value}:${name.value}_${scalaBinaryVersion.value}",
  bintrayOrganization := Some("allenai"),
  bintrayRepository := "maven",
  libraryDependencies ++= Seq(
    "io.spray" %% "spray-json" % "1.3.5",
    "com.github.scopt" %% "scopt" % "3.7.1",
    "ch.qos.logback" % "logback-classic" % "1.1.7",
    "org.slf4j" % "jcl-over-slf4j" % "1.7.21",
    "org.apache.pdfbox" % "pdfbox" % "2.0.1",
    "org.apache.pdfbox" % "fontbox" % "2.0.1",
    "com.typesafe" % "config" % "1.3.0",

    // So PDFBox can parse more image formats
    // These are disabled by default, because they are not licensed flexibly enough.
    //"com.github.jai-imageio" % "jai-imageio-core" % "1.2.1",
    //"com.github.jai-imageio" % "jai-imageio-jpeg2000" % "1.3.0", // For handling jpeg2000 images
    //"com.levigo.jbig2" % "levigo-jbig2-imageio" % "1.6.5", // For handling jbig2 images

    // So PDFBox can parse security enabled but still readable PDFs
    "org.bouncycastle" % "bcprov-jdk15on" % "1.54",
    "org.bouncycastle" % "bcmail-jdk15on" % "1.54",
    "org.bouncycastle" % "bcpkix-jdk15on" % "1.54"
  ),

  pomExtra :=
      <developers>
        <developer>
          <id>allenai-dev-role</id>
          <name>Allen Institute for Artificial Intelligence</name>
          <email>dev-role@allenai.org</email>
        </developer>
      </developers>
)

lazy val root = (project in file("."))
    .settings(projectSettings)

mainClass in (Compile, run) := Some("org.allenai.pdffigures2.FigureExtractorBatchCli")
mainClass in assembly := Some("org.allenai.pdffigures2.FigureExtractorBatchCli")

assemblyMergeStrategy in assembly := {
  case PathList("org", "apache", "commons", xs @ _*) => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
