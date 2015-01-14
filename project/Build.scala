import sbt._
import Keys._
import play.PlayScala
import play.PlayImport._
import java.io.PrintWriter
import java.io.File

object ApplicationBuild extends Build {

  val appName         = "CashyLive"
  val appVersion      = "0.0.1." + "git rev-parse --short HEAD".!!.trim + ".SNAPSHOT"

  val appDependencies = Seq(
    "org.apache.httpcomponents" % "httpclient" % "4.3.6"
  )

  val main = Project(appName, file(".")).enablePlugins(PlayScala).settings(
      javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6"),
      libraryDependencies ++= appDependencies,
      scalaVersion := "2.11.2",
      scalacOptions ++= Seq("-feature", "-deprecation", "-Xfatal-warnings"),
      version := appVersion
  )

}
