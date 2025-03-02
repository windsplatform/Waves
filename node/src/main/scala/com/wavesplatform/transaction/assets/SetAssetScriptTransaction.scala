package com.wavesplatform.transaction.assets

import com.wavesplatform.account._
import com.wavesplatform.crypto
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.lang.script.Script
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.serialization.impl.SetAssetScriptTxSerializer
import com.wavesplatform.transaction.validation.TxValidator
import com.wavesplatform.transaction.validation.impl.SetAssetScriptTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

import scala.reflect.ClassTag
import scala.util.Try

case class SetAssetScriptTransaction(
    version: TxVersion,
    sender: PublicKey,
    asset: IssuedAsset,
    script: Option[Script],
    fee: TxAmount,
    timestamp: TxTimestamp,
    proofs: Proofs
) extends VersionedTransaction
    with ProvenTransaction
    with TxWithFee.InWaves
    with FastHashId
    with LegacyPBSwitch.V2 {

  //noinspection TypeAnnotation
  override val builder = SetAssetScriptTransaction

  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(builder.serializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(builder.serializer.toBytes(this))
  override val json: Coeval[JsObject]         = Coeval.evalOnce(builder.serializer.toJson(this))

  override val checkedAssets: Seq[IssuedAsset] = Seq(asset)
  override val chainByte: Option[Byte]         = Some(AddressScheme.current.chainId)
}

object SetAssetScriptTransaction extends TransactionParser {
  override type TransactionT = SetAssetScriptTransaction

  val typeId: TxType = 15

  override val supportedVersions: Set[TxVersion] = Set(1)

  override val classTag = ClassTag(classOf[SetAssetScriptTransaction])

  implicit val validator: TxValidator[SetAssetScriptTransaction] = SetAssetScriptTxValidator

  implicit def sign(tx: SetAssetScriptTransaction, privateKey: PrivateKey): SetAssetScriptTransaction =
    tx.copy(proofs = Proofs(crypto.sign(privateKey, tx.bodyBytes())))

  val serializer = SetAssetScriptTxSerializer

  override def parseBytes(bytes: Array[TxVersion]): Try[SetAssetScriptTransaction] =
    serializer.parseBytes(bytes)

  def create(
      version: TxVersion,
      sender: PublicKey,
      assetId: IssuedAsset,
      script: Option[Script],
      fee: TxAmount,
      timestamp: TxTimestamp,
      proofs: Proofs
  ): Either[ValidationError, TransactionT] =
    SetAssetScriptTransaction(1.toByte, sender, assetId, script, fee, timestamp, proofs).validatedEither

  def signed(
      version: TxVersion,
      sender: PublicKey,
      asset: IssuedAsset,
      script: Option[Script],
      fee: TxAmount,
      timestamp: TxTimestamp,
      signer: PrivateKey
  ): Either[ValidationError, TransactionT] =
    create(version, sender, asset, script, fee, timestamp, Proofs.empty).map(_.signWith(signer))

  def selfSigned(
      version: TxVersion,
      sender: KeyPair,
      asset: IssuedAsset,
      script: Option[Script],
      fee: TxAmount,
      timestamp: TxTimestamp
  ): Either[ValidationError, SetAssetScriptTransaction] =
    signed(version, sender, asset, script, fee, timestamp, sender)
}
