package com.wavesplatform.api.http.requests

import com.wavesplatform.account.PublicKey
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.assets.ReissueTransaction
import io.swagger.annotations.ApiModelProperty
import play.api.libs.json.{Format, Json}

object SignedReissueV1Request {
  implicit val assetReissueRequestReads: Format[SignedReissueV1Request] = Json.format
}

case class SignedReissueV1Request(
    @ApiModelProperty(value = "Base58 encoded Issuer public key", required = true)
    senderPublicKey: String,
    @ApiModelProperty(value = "Base58 encoded Asset ID", required = true)
    assetId: String,
    @ApiModelProperty(required = true, example = "1000000")
    quantity: Long,
    @ApiModelProperty(required = true)
    reissuable: Boolean,
    @ApiModelProperty(required = true)
    fee: Long,
    @ApiModelProperty(required = true)
    timestamp: Long,
    @ApiModelProperty(required = true)
    signature: String
) {
  def toTx: Either[ValidationError, ReissueTransaction] =
    for {
      _sender    <- PublicKey.fromBase58String(senderPublicKey)
      _signature <- parseBase58(signature, "invalid.signature", SignatureStringLength)
      _assetId   <- parseBase58ToAsset(assetId)
      _t         <- ReissueTransaction.create(1.toByte, _sender, _assetId, quantity, reissuable, fee, timestamp, Proofs(_signature))
    } yield _t
}
