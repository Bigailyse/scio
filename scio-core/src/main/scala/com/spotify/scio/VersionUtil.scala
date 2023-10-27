/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.http.{GenericUrl, HttpRequest}
import com.google.api.client.json.JsonObjectParser
import com.google.api.client.json.gson.GsonFactory
import org.apache.beam.sdk.util.ReleaseInfo
import org.apache.beam.sdk.{PipelineResult, PipelineRunner}
import org.slf4j.LoggerFactory

import scala.io.AnsiColor._
import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.util.Try

private[scio] object VersionUtil {
  case class SemVer(major: Int, minor: Int, rev: Int, suffix: String) extends Ordered[SemVer] {
    def compare(that: SemVer): Int =
      Ordering[(Int, Int, Int, String)].compare(SemVer.unapply(this).get, SemVer.unapply(that).get)
  }

  private[this] val Timeout = 3000
  private[this] val Url = "https://api.github.com/repos/spotify/scio/releases"

  /**
   * example versions: version = "0.10.0-beta1+42-828dca9a-SNAPSHOT" version = "0.10.0-beta1"
   * version = "0.10.0-SNAPSHOT"
   */
  private[this] val Pattern = """v?(\d+)\.(\d+).(\d+)((-\w+)?(\+\d+-\w+(\+\d+-\d+)?(-\w+)?)?)?""".r
  private[this] val Logger = LoggerFactory.getLogger(this.getClass)

  private[this] val MessagePattern: (String, String) => String = (version, url) => s"""
       | $YELLOW>$BOLD Scio $version introduced some breaking changes in the API.$RESET
       | $YELLOW>$RESET Follow the migration guide to upgrade: $url.
       | $YELLOW>$RESET Scio provides automatic migration rules (See migration guide).
      """.stripMargin
  private[this] val NewerVersionPattern: (String, String) => String = (current, v) => s"""
      | $YELLOW>$BOLD A newer version of Scio is available: $current -> $v$RESET
      | $YELLOW>$RESET Use `-Dscio.ignoreVersionWarning=true` to disable this check.$RESET
      |""".stripMargin

  private lazy val latest: Option[String] = Try {
    val transport = new NetHttpTransport()
    val response = transport
      .createRequestFactory { (request: HttpRequest) =>
        request.setConnectTimeout(Timeout)
        request.setReadTimeout(Timeout)
        request.setParser(new JsonObjectParser(GsonFactory.getDefaultInstance))
        ()
      }
      .buildGetRequest(new GenericUrl(Url))
      .execute()
      .parseAs(classOf[java.util.List[java.util.Map[String, AnyRef]]])
    response.asScala
      .filter(node => !node.get("prerelease").asInstanceOf[Boolean])
      .find(node => !node.get("draft").asInstanceOf[Boolean])
      .map(latestNode => latestNode.get("tag_name").asInstanceOf[String])
  }.toOption.flatten

  private def parseVersion(version: String): SemVer = {
    val m = Pattern.findFirstMatchIn(version).get
    // higher value for no "-SNAPSHOT"
    val snapshot = if (!m.group(4).isEmpty()) m.group(4).toUpperCase else "\uffff"
    SemVer(m.group(1).toInt, m.group(2).toInt, m.group(3).toInt, snapshot)
  }

  private[scio] def ignoreVersionCheck: Boolean =
    sys.props.get("scio.ignoreVersionWarning").exists(_.trim == "true")

  private val migrationPages = Set(7, 8, 9, 10, 12, 13)
  private def messages(current: SemVer, latest: SemVer): Option[String] = {
    Some(latest.minor)
      .filter(migrationPages.contains)
      .filter(_ > current.minor)
      .map { minor =>
        val minorVersion = s"0.$minor"
        val fullVersion = s"v.0.$minor.0"
        MessagePattern(
          minorVersion,
          s"https://spotify.github.io/scio/releases/migrations/v$fullVersion-Migration-Guide"
        )
      }
  }

  def checkVersion(
    current: String,
    latestOverride: Option[String] = None,
    ignore: Boolean = ignoreVersionCheck
  ): Seq[String] =
    if (ignore) {
      Nil
    } else {
      val buffer = mutable.Buffer.empty[String]
      val v1 = parseVersion(current)
      if (v1.suffix.contains("-SNAPSHOT")) {
        buffer.append(s"Using a SNAPSHOT version of Scio: $current")
      }
      latestOverride.orElse(latest).foreach { v =>
        val v2 = parseVersion(v)
        if (v2 > v1) {
          buffer.append(NewerVersionPattern(current, v))
          messages(v1, v2).foreach(buffer.append(_))
        }
      }
      buffer.toSeq
    }

  def checkVersion(): Unit = checkVersion(BuildInfo.version).foreach(Logger.warn)

  def checkRunnerVersion(runner: Class[_ <: PipelineRunner[_ <: PipelineResult]]): Unit = {
    val name = runner.getSimpleName
    val version = ReleaseInfo.getReleaseInfo.getVersion
    if (version != BuildInfo.beamVersion) {
      Logger.warn(
        s"Mismatched version for $name, expected: ${BuildInfo.beamVersion}, actual: $version"
      )
    }
  }
}
