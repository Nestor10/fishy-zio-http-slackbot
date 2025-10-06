package com.nestor10.slackbot.domain.slack

import zio.json.*

// Acknowledgment response
case class AckResponse(
    envelope_id: String,
    payload: Option[String] = None // Optional payload if acceptsResponsePayload is true
)

object AckResponse:
  implicit val encoder: JsonEncoder[AckResponse] = DeriveJsonEncoder.gen[AckResponse]
