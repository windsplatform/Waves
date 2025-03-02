package com.wavesplatform.transaction.smart

import com.google.common.io.ByteStreams
import com.wavesplatform.account.{AddressOrAlias, PublicKey}
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.crypto
import com.wavesplatform.features.MultiPaymentPolicyProvider._
import com.wavesplatform.lang.directives.DirectiveSet
import com.wavesplatform.lang.v1.traits.Environment.InputEntity
import com.wavesplatform.lang.v1.traits._
import com.wavesplatform.lang.v1.traits.domain.Recipient._
import com.wavesplatform.lang.v1.traits.domain._
import com.wavesplatform.state._
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.assets.exchange.Order
import com.wavesplatform.transaction.{Asset, Transaction}
import monix.eval.Coeval
import shapeless._

import scala.util.Try

object WavesEnvironment {
  type In = Transaction :+: Order :+: PseudoTx :+: CNil
}

class WavesEnvironment(
    nByte: Byte,
    in: Coeval[Environment.InputEntity],
    h: Coeval[Int],
    blockchain: Blockchain,
    address: Coeval[ByteStr],
    ds: DirectiveSet
) extends Environment[Id] {

  override def height: Long = h()

  override def multiPaymentAllowed: Boolean = blockchain.allowsMultiPayment

  override def transactionById(id: Array[Byte]): Option[Tx] =
    blockchain
      .transactionInfo(ByteStr(id))
      .map(_._2)
      .map(tx => RealTransactionWrapper(tx, blockchain, ds.stdLibVersion, paymentTarget(ds, Some(ByteStr.empty))).explicitGet())

  override def inputEntity: InputEntity =
    in.value

  override def transferTransactionById(id: Array[Byte]): Option[Tx] =
    blockchain
      .transferById(id)
      .map(t => RealTransactionWrapper.mapTransferTx(t._2))

  override def data(recipient: Recipient, key: String, dataType: DataType): Option[Any] = {
    for {
      address <- recipient match {
        case Address(bytes) =>
          com.wavesplatform.account.Address
            .fromBytes(bytes.arr)
            .toOption
        case Alias(name) =>
          com.wavesplatform.account.Alias
            .create(name)
            .flatMap(blockchain.resolveAlias)
            .toOption
      }
      data <- blockchain
        .accountData(address, key)
        .map((_, dataType))
        .flatMap {
          case (IntegerDataEntry(_, value), DataType.Long)     => Some(value)
          case (BooleanDataEntry(_, value), DataType.Boolean)  => Some(value)
          case (BinaryDataEntry(_, value), DataType.ByteArray) => Some(ByteStr(value.arr))
          case (StringDataEntry(_, value), DataType.String)    => Some(value)
          case _                                               => None
        }
    } yield data
  }
  override def resolveAlias(name: String): Either[String, Recipient.Address] =
    blockchain
      .resolveAlias(com.wavesplatform.account.Alias.create(name).explicitGet())
      .left
      .map(_.toString)
      .right
      .map(a => Recipient.Address(ByteStr(a.bytes.arr)))

  override def chainId: Byte = nByte

  override def accountBalanceOf(addressOrAlias: Recipient, maybeAssetId: Option[Array[Byte]]): Either[String, Long] = {
    (for {
      aoa <- addressOrAlias match {
        case Address(bytes) => AddressOrAlias.fromBytes(bytes.arr, position = 0).map(_._1)
        case Alias(name)    => com.wavesplatform.account.Alias.create(name)
      }
      address <- blockchain.resolveAlias(aoa)
      balance = blockchain.balance(address, Asset.fromCompatId(maybeAssetId.map(ByteStr(_))))
    } yield balance).left.map(_.toString)
  }

  override def transactionHeightById(id: Array[Byte]): Option[Long] =
    blockchain.transactionHeight(ByteStr(id)).map(_.toLong)

  override def tthis: Address = Recipient.Address(address())

  override def assetInfoById(id: Array[Byte]): Option[domain.ScriptAssetInfo] = {
    blockchain.assetDescription(IssuedAsset(id)).map { assetDesc =>
      ScriptAssetInfo(
        id = id,
        quantity = assetDesc.totalVolume.toLong,
        decimals = assetDesc.decimals,
        issuer = Address(assetDesc.issuer.toAddress.bytes),
        issuerPk = assetDesc.issuer,
        reissuable = assetDesc.reissuable,
        scripted = assetDesc.script.nonEmpty,
        sponsored = assetDesc.sponsorship != 0
      )
    }
  }

  override def lastBlockOpt(): Option[BlockInfo] =
    blockchain.lastBlock.map(block => toBlockInfo(block.header, height.toInt))

  override def blockInfoByHeight(blockHeight: Int): Option[BlockInfo] =
    blockchain.blockInfo(blockHeight).map(blockHAndSize => toBlockInfo(blockHAndSize.header, blockHeight))

  private def toBlockInfo(blockH: BlockHeader, bHeight: Int) = {
    BlockInfo(
      timestamp = blockH.timestamp,
      height = bHeight,
      baseTarget = blockH.baseTarget,
      generationSignature = blockH.generationSignature,
      generator = blockH.generator.toAddress.bytes,
      generatorPublicKey = ByteStr(blockH.generator)
    )
  }

  override def blockHeaderParser(bytes: Array[Byte]): Option[domain.BlockHeader] =
    Try {
      val (header, transactionCount, signature) = readHeaderOnly(bytes)

      domain.BlockHeader(
        header.timestamp,
        header.version,
        header.reference,
        header.generator.toAddress.bytes,
        header.generator.bytes,
        signature,
        header.baseTarget,
        header.generationSignature,
        transactionCount,
        header.featureVotes.map(_.toLong).sorted
      )
    }.toOption

  private def readHeaderOnly(bytes: Array[Byte]): (BlockHeader, Int, ByteStr) = {
    val ndi = ByteStreams.newDataInput(bytes)

    val version   = ndi.readByte()
    val timestamp = ndi.readLong()

    val referenceArr = new Array[Byte](crypto.SignatureLength)
    ndi.readFully(referenceArr)

    val baseTarget = ndi.readLong()

    val genSigLength = if (version < Block.ProtoBlockVersion) Block.GenerationSignatureLength else Block.GenerationVRFSignatureLength
    val genSig       = new Array[Byte](genSigLength)
    ndi.readFully(genSig)

    val transactionCount = {
      if (version == Block.GenesisBlockVersion || version == Block.PlainBlockVersion) ndi.readByte()
      else ndi.readInt()
    }
    val featureVotesCount = ndi.readInt()
    val featureVotes      = List.fill(featureVotesCount)(ndi.readShort())

    val rewardVote = if (version > Block.NgBlockVersion) ndi.readLong() else -1L

    val generator = new Array[Byte](crypto.KeyLength)
    ndi.readFully(generator)

    val merkle = if (version > Block.RewardBlockVersion) {
      val result = new Array[Byte](ndi.readInt())
      ndi.readFully(result)
      result
    } else Array.emptyByteArray

    val signature = new Array[Byte](crypto.SignatureLength)
    ndi.readFully(signature)

    val header = BlockHeader(
      version,
      timestamp,
      ByteStr(referenceArr),
      baseTarget,
      ByteStr(genSig),
      PublicKey(ByteStr(generator)),
      featureVotes,
      rewardVote,
      ByteStr(merkle)
    )
    (header, transactionCount, ByteStr(signature))
  }
}
