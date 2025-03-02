package com.wavesplatform.transaction.transfer

import cats.implicits._
import com.wavesplatform.account.{AddressOrAlias, KeyPair, PrivateKey, PublicKey}
import com.wavesplatform.crypto
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError._
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.serialization.impl.MassTransferTxSerializer
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.validation.TxValidator
import com.wavesplatform.transaction.validation.impl.MassTransferTxValidator
import io.swagger.annotations.{ApiModel, ApiModelProperty}
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json}

import scala.annotation.meta.field
import scala.reflect.ClassTag
import scala.util.{Either, Try}

case class MassTransferTransaction(
    version: TxVersion,
    sender: PublicKey,
    assetId: Asset,
    transfers: Seq[ParsedTransfer],
    fee: TxAmount,
    timestamp: TxTimestamp,
    attachment: Array[Byte],
    proofs: Proofs
) extends ProvenTransaction
    with VersionedTransaction
    with TxWithFee.InWaves
    with FastHashId
    with LegacyPBSwitch.V2 {

  //noinspection TypeAnnotation
  override val builder = MassTransferTransaction

  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(builder.serializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(builder.serializer.toBytes(this))
  override val json: Coeval[JsObject]         = Coeval.evalOnce(builder.serializer.toJson(this))

  def compactJson(recipients: Set[AddressOrAlias]): JsObject =
    json() ++ Json.obj("transfers" -> MassTransferTxSerializer.transfersJson(transfers.filter(t => recipients.contains(t.address))))

  override def checkedAssets: Seq[IssuedAsset] = assetId match {
    case Waves          => Seq()
    case a: IssuedAsset => Seq(a)
  }
}

object MassTransferTransaction extends TransactionParser {
  override type TransactionT = MassTransferTransaction

  val MaxTransferCount = 100

  override val typeId: TxType                    = 11
  override val supportedVersions: Set[TxVersion] = Set(1)
  override val classTag                          = ClassTag(classOf[MassTransferTransaction])

  implicit val validator: TxValidator[MassTransferTransaction] = MassTransferTxValidator

  implicit def sign(tx: MassTransferTransaction, privateKey: PrivateKey): MassTransferTransaction =
    tx.copy(proofs = Proofs(crypto.sign(privateKey, tx.bodyBytes())))

  //noinspection TypeAnnotation
  val serializer = MassTransferTxSerializer

  override def parseBytes(bytes: Array[Byte]): Try[MassTransferTransaction] =
    serializer.parseBytes(bytes)

  @ApiModel
  case class Transfer(
      @(ApiModelProperty @field)(dataType = "string", example = "3Mciuup51AxRrpSz7XhutnQYTkNT9691HAk", required = true) recipient: String,
      @(ApiModelProperty @field)(dataType = "long", example = "3000000000", required = true) amount: Long
  )

  object Transfer {
    implicit val jsonFormat = Json.format[Transfer]
  }

  case class ParsedTransfer(address: AddressOrAlias, amount: Long)

  def create(
      version: TxVersion,
      sender: PublicKey,
      assetId: Asset,
      transfers: Seq[ParsedTransfer],
      fee: TxAmount,
      timestamp: TxTimestamp,
      attachment: Array[Byte],
      proofs: Proofs
  ): Either[ValidationError, TransactionT] =
    MassTransferTransaction(version, sender, assetId, transfers, fee, timestamp, attachment, proofs).validatedEither

  def signed(
      version: TxVersion,
      sender: PublicKey,
      assetId: Asset,
      transfers: Seq[ParsedTransfer],
      fee: TxAmount,
      timestamp: TxTimestamp,
      attachment: Array[Byte],
      signer: PrivateKey
  ): Either[ValidationError, TransactionT] =
    create(version, sender, assetId, transfers, fee, timestamp, attachment, Proofs.empty).map(_.signWith(signer))

  def selfSigned(
      version: TxVersion,
      sender: KeyPair,
      assetId: Asset,
      transfers: Seq[ParsedTransfer],
      fee: TxAmount,
      timestamp: TxTimestamp,
      attachment: Array[Byte]
  ): Either[ValidationError, TransactionT] =
    signed(version, sender, assetId, transfers, fee, timestamp, attachment, sender)

  def parseTransfersList(transfers: List[Transfer]): Validation[List[ParsedTransfer]] = {
    transfers.traverse {
      case Transfer(recipient, amount) =>
        AddressOrAlias.fromString(recipient).map(ParsedTransfer(_, amount))
    }
  }

}
