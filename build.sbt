enablePlugins(JavaAppPackaging)

name := "dotfiddle"
organization := "org.im"
version := "1.0"
scalaVersion := "2.11.8"

scalacOptions := Seq(
  "-unchecked",
  "-deprecation",
  "-Xexperimental",
  "-encoding", "utf8",
  "-Xfuture",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard")

resolvers += Resolver.url("file://" + Path.userHome.absolutePath + "/.ivy/local")
resolvers += Resolver.sonatypeRepo("releases")
resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository"
resolvers += Resolver.bintrayRepo("scalaz", "releases")


libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" %  "latest.release" % "test"
    ,"org.scala-lang.modules" %% "scala-xml" % "latest.release"
    ,"com.typesafe" % "config" %  "latest.release"
    ,"com.github.scopt" %% "scopt" % "latest.release"
    ,"ch.qos.logback" % "logback-classic" % "latest.release"
    ,"ch.qos.logback" % "logback-core" % "latest.release"
    ,"net.databinder.dispatch" %% "dispatch-core" % "latest.release"
    ,"com.typesafe.scala-logging" %% "scala-logging" % "latest.release"
    , "commons-codec" % "commons-codec" % "latest.release"
    ,"org.scala-lang.modules" %% "scala-async" % "latest.release"
    ,"org.scalafx" %% "scalafx" % "8.0.92-R10"
    ,"org.fxmisc.richtext" % "richtextfx" % "latest.version"
    ,"org.controlsfx" % "controlsfx" % "latest.version"
)

EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

EclipseKeys.withSource := true

fork in run := true

javaOptions in run ++= Seq("-Xmx4G","-Djdk.gtk.version=3")

libraryDependencies += "com.lihaoyi" % "ammonite" % "0.7.4" % "test" cross CrossVersion.full

initialCommands in (Test, console) := """ammonite.Main().run()"""
