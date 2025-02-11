package org.matthicks.mailgun

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.youi.client.HttpClient
import io.youi.http.content.Content
import io.youi.http.Headers

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import io.youi.net._

class Mailgun(domain: String, apiKey: String, region: Option[String] = None) {
  private implicit val customConfig: Configuration = Configuration.default.withSnakeCaseMemberNames.withDefaults
  private lazy val url: URL = URL(s"https://api.${region.map(r => s"$r.").getOrElse("")}mailgun.net/v3/$domain/messages")

  private lazy val encodedKey = new String(Base64.getEncoder.encode(s"api:$apiKey".getBytes(StandardCharsets.UTF_8)), "utf-8")
  private lazy val client = HttpClient
    .url(url)
    .post
    .header(Headers.Request.Authorization(s"Basic $encodedKey"))

  def send(message: Message): Future[MessageResponse] = {
    var content = Content.form

    def add(key: String, value: Any): Unit = {
      content = content.withString(key, value.toString)
    }

    add("from", message.from)
    add("subject", message.subject)
    message.to.foreach { to =>
      add("to", to)
    }
    message.cc.foreach { cc =>
      add("cc", cc)
    }
    message.bcc.foreach { bcc =>
      add("bcc", bcc)
    }
    message.tags.foreach { tag =>
      add("o:tag", tag)
    }
    message.campaignId.foreach { campaignId =>
      add("o:campaign", campaignId)
    }
    message.dkim.foreach { dkim =>
      add("o:dkim", if (dkim) "yes" else "no")
    }
    message.deliveryTime.foreach { deliveryTime =>
      add("o.deliverytime", deliveryTime)
    }
    if (message.testMode) {
      add("o:testmode", "yes")
    }
    message.tracking.foreach { tracking =>
      add("o:tracking", if (tracking) "yes" else "no")
    }
    message.trackingClicks match {
      case TrackingClicks.Default => // Nothing needs to be set
      case TrackingClicks.Yes => add("o:tracking-clicks", "yes")
      case TrackingClicks.No => add("o:tracking-clicks", "no")
      case TrackingClicks.HTMLOnly => add("o:tracking-clicks", "htmlonly")
    }
    message.trackingOpens.foreach { trackingOpens =>
      add("o:tracking-opens", if (trackingOpens) "yes" else "no")
    }
    if (message.requireTLS) {
      add("o:require-tls", "yes")
    }
    if (message.skipVerification) {
      add("o:skip-verification", "yes")
    }
    message.customHeaders.foreach {
      case (key, value) => add(s"h:$key", value)
    }
    message.customData.foreach {
      case (key, value) => add(s"v:$key", value)
    }
    message.text.foreach { text =>
      add("text", text)
    }
    message.html.foreach { text =>
      add("html", text)
    }
    message.attachments.foreach { attachment =>
      val contentType = Headers.`Content-Type`(ContentType.byFileName(attachment.file.getName))
      content = content.withFile("attachment", attachment.file.getName, attachment.file, Headers.empty.withHeader(contentType))
    }
    message.inline.foreach { inline =>
      val contentType = Headers.`Content-Type`(ContentType.byFileName(inline.file.getName))
      content = content.withFile("inline", inline.file.getName, inline.file, Headers.empty.withHeader(contentType))
    }

    client
      .content(content)
      .send()
      .map { response =>
        val responseJson = response.content.map(_.asString).getOrElse("")
        if (responseJson.isEmpty) throw new RuntimeException(s"No content received in response for ${client.url}.")
        parser.parse(responseJson) match {
          case Left(error) => throw new RuntimeException(s"Failed to parse JSON response: $responseJson", error)
          case Right(json) => {
            val responseDecoder: Decoder[MessageResponse] = deriveDecoder[MessageResponse]
            responseDecoder.decodeJson(json) match {
              case Left(error) => throw new RuntimeException(s"Failed to convert JSON response to MessageResponse: $responseJson", error)
              case Right(result) => result
            }
          }
        }
      }
  }
}
